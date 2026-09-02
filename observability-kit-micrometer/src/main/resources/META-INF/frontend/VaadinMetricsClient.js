// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// In-browser collector for observability-kit. Injected per UI by
// MetricsCollectorElement via Page.executeJs. The IIFE is idempotent so the
// re-attach path does not double-install hooks.
(function () {
  if (window.__vaadinMicrometerInstalled) {
    return;
  }
  window.__vaadinMicrometerInstalled = true;

  var COLLECTOR_TAG = 'vaadin-metrics-collector';
  var BUFFER_MAX = 200;
  var FLUSH_INTERVAL_MS = 5000;
  // Per-tab, and deliberately not localStorage: a buffer is about the tab that
  // filled it, and sharing one across tabs would need locking to avoid two
  // tabs flushing the same samples.
  var STORAGE_KEY = 'vaadin.observability.buffer';

  var CONNECTED = 'connected';
  var LOADING = 'loading';
  // The states in which the browser cannot reach the server. Deliberately not
  // read off ConnectionStateStore.offline: the store counts LOADING as online,
  // so its flag goes false for the duration of every reconnect attempt even
  // though the outage is still running.
  var OFFLINE_STATES = ['connection-lost', 'reconnecting'];

  var CONNECTION = 'vaadin.client.connection';
  var CONNECTION_DOWNTIME = 'vaadin.client.connection.downtime';
  var CLIENT_ERRORS = 'vaadin.client.errors';

  var buffer = [];

  // The batch handed to the server and not yet answered for, if any. It stays
  // in the persisted copy until the answer arrives, since the send is
  // asynchronous and a tab that closes while it is in flight takes the request
  // with it. Only ever one batch: a server that stops answering then costs one
  // batch of memory rather than another one every flush interval.
  var inFlight = null;

  // The last connection state that was not LOADING, maintained by the
  // connection listener below and read by the flush guard. Undefined until the
  // store is found, which reads as online -- the behaviour when a page has no
  // store at all.
  var lastState;

  // Time this page has spent unable to reach the server: the stretches that
  // have ended, plus when the current one began (null while online). Both are
  // maintained by the connection listener.
  var offlineMs = 0;
  var offlineSince = null;

  // What each buffered sample's offline clock read when it was taken. Held
  // beside the samples rather than on them, so nothing internal travels to
  // the server; entries disappear with the samples they key.
  var offlineBaseline = new WeakMap();

  // The clock every *duration* here is measured on. Date.now() is not
  // monotonic -- an NTP step or a manual clock change during an outage would
  // move it, and the two things being timed are exactly the things that
  // outlast an outage -- so durations come from performance.now(), which only
  // goes forward. Date.now() stays for `ts`, which is a wall-clock instant and
  // wants to be comparable with one.
  function monotonicNow() {
    try {
      return performance.now();
    } catch (e) {
      return Date.now();
    }
  }

  function connectionStore() {
    return (window.Vaadin && window.Vaadin.connectionState) || null;
  }

  // Flow drives the store through LOADING around every UIDL request to run the
  // loading indicator, so LOADING is not a connection state and is never
  // reported. Ignoring it in both directions is what makes a retry during an
  // outage read as an attempt rather than a recovery: the outage ends when the
  // store reaches CONNECTED, not when a request starts.
  function isLoading(state) {
    return String(state || '').toLowerCase() === LOADING;
  }

  function normalizeState(state) {
    var value = String(state || '').toLowerCase();
    return value === LOADING ? CONNECTED : value;
  }

  function isOfflineState(state) {
    return OFFLINE_STATES.indexOf(state) >= 0;
  }

  // The guard on every send, and on whether the buffer is worth persisting.
  // Reads the LOADING-free view of the state rather than the store's own
  // `offline`, so a reconnect attempt in flight still counts as an outage: a
  // flush during that window would empty the buffer into Flow's pending-message
  // queue, and the persist that follows would clear the copy that a reload is
  // supposed to find.
  function offline() {
    return isOfflineState(lastState);
  }

  // How much of this page's life has been spent offline, as of now. What a
  // sample reports as ageMs is the difference between this at flush time and
  // this when it was taken -- so a routine error reports 0 however long it
  // waited for the next periodic flush, and only an error the browser could
  // not send reports anything at all. Wall-clock buffering delay would make
  // every error look like it happened during an outage.
  function offlineElapsed() {
    if (offlineSince === null) {
      return offlineMs;
    }
    return offlineMs + Math.max(0, monotonicNow() - offlineSince);
  }

  // The offline time a sample waited through. Samples restored from storage
  // are re-baselined on the way in, so a missing baseline means a sample this
  // code did not put in the buffer: claim no outage for it.
  function bufferedMs(sample) {
    var base = offlineBaseline.get(sample);
    return base === undefined ? 0 : Math.max(0, offlineElapsed() - base);
  }

  function pushSample(name, tags, valueMs, detail) {
    if (typeof valueMs !== 'number' || isNaN(valueMs) || valueMs < 0) {
      valueMs = 0;
    }
    if (buffer.length >= BUFFER_MAX) {
      makeRoom();
    }
    var sample = {
      name: name,
      tags: tags || {},
      valueMs: valueMs,
      ts: Date.now(),
      ageMs: 0
    };
    if (detail) {
      sample.detail = detail;
    }
    offlineBaseline.set(sample, offlineElapsed());
    buffer.push(sample);
    if (offline()) {
      // Only worth the write while the samples are at risk: a reload during an
      // outage would otherwise lose exactly the reports that explain it.
      persist();
    }
  }

  function currentRoute() {
    return window.location.pathname || '/';
  }

  // The buffer and everything still in flight, held where a reload can find it
  // again. Best-effort: storage is unavailable in some privacy modes and full
  // in others, and neither is worth failing a measurement over.
  function persist() {
    try {
      var pending = inFlight === null ? buffer : inFlight.concat(buffer);
      if (pending.length === 0) {
        window.sessionStorage.removeItem(STORAGE_KEY);
      } else {
        // Stamp the wait accrued so far on the way out. In memory the
        // baselines are what count, but they die with the page, and a page
        // only loads while the server is reachable -- so without this a
        // report that survived a crash mid-outage would come back reporting
        // no outage at all, which is the one case it exists to describe.
        pending.forEach(function (sample) {
          sample.ageMs = bufferedMs(sample);
        });
        window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending));
      }
    } catch (e) {
      /* storage unavailable or full, keep the in-memory buffer */
    }
  }

  function restore() {
    try {
      var stored = window.sessionStorage.getItem(STORAGE_KEY);
      if (!stored) {
        return;
      }
      window.sessionStorage.removeItem(STORAGE_KEY);
      var samples = JSON.parse(stored);
      if (!Array.isArray(samples)) {
        return;
      }
      // Oldest first, ahead of anything this page load has produced.
      // Truncated from the tail, matching the send priority: the head of a
      // persisted buffer is the transition that started the outage.
      buffer = samples.concat(buffer).slice(0, BUFFER_MAX);
      // Re-baselined against this page's offline clock, which starts at zero
      // -- but backdated by the wait each sample already carries, so it keeps
      // what it accrued in the load that persisted it and goes on accruing if
      // this load loses the server too. A backdated baseline is negative,
      // which is exactly what bufferedMs() needs to return the stored wait.
      var restoredAt = offlineElapsed();
      buffer.forEach(function (sample) {
        var carried = typeof sample.ageMs === 'number' && isFinite(sample.ageMs) && sample.ageMs > 0 ? sample.ageMs : 0;
        offlineBaseline.set(sample, restoredAt - carried);
      });
      // Put it straight back: a second reload before the next flush must
      // not be the thing that loses it.
      persist();
    } catch (e) {
      /* unreadable buffer: drop it rather than fail the collector */
    }
  }

  // What a sample is worth when there is not room for all of them. The server
  // keeps the head of an oversized batch and counts the rest as
  // vaadin.client.throttled, and the buffer has a hard cap of its own, so both
  // ends of the pipe need the same answer to "which one goes".
  //
  // 0 -- the connection samples. A post-outage flush is the largest batch the
  //      collector ever sends and the one carrying the recovery transition and
  //      the downtime; losing those loses the outage itself.
  // 1 -- the browser errors. Each one is a failure that happened to somebody
  //      rather than a point in a distribution, and an outage is exactly when
  //      they pile up behind the samples explaining it. Ranking them with the
  //      vitals would have a full buffer drop the one error behind a user's
  //      report while younger vitals survive.
  // 2 -- everything else. A lost bootstrap, navigation or vitals sample is a
  //      gap in a distribution that the next page load refills.
  function priority(sample) {
    if (sample.name === CONNECTION || sample.name === CONNECTION_DOWNTIME) {
      return 0;
    }
    return sample.name === CLIENT_ERRORS ? 1 : 2;
  }

  /** Stable ordering by priority, so the head of a batch is what must survive. */
  function priorityFirst(batch) {
    var buckets = [[], [], []];
    batch.forEach(function (sample) {
      buckets[priority(sample)].push(sample);
    });
    return buckets[0].concat(buckets[1], buckets[2]);
  }

  // The buffer is full. Drop the oldest of the least valuable class rather than
  // simply the oldest: through a long outage the oldest sample is the
  // connection-lost transition, and it is the one that explains everything
  // buffered after it.
  function makeRoom() {
    var worst = 0;
    for (var i = 1; i < buffer.length; i++) {
      if (priority(buffer[i]) > priority(buffer[worst])) {
        worst = i;
      }
    }
    buffer.splice(worst, 1);
  }

  // Hands the buffer to the server, and keeps a copy in storage until the
  // server says it arrived. The send is asynchronous however it ends -- Flow
  // queues the message and the promise it returns settles on the server's
  // answer -- and a tab that closes in that window takes the request with it,
  // because the browser cancels an in-flight send during unload. Clearing
  // storage at send time would therefore lose exactly the last batch, the one
  // carrying the error a user is about to report. The price is a duplicate
  // when the send arrived and the answer did not, which is the cheaper of the
  // two failures.
  function flush() {
    if (buffer.length === 0) {
      return;
    }
    if (offline()) {
      // A report needs the very connection it is about. Sending now would fail
      // and lose the batch that explains the outage; hold it until recovery.
      persist();
      return;
    }
    if (inFlight !== null) {
      // One batch out at a time. Until the server has answered for the last
      // one, the persisted copy is all that is left of it, and a second send
      // would take that copy over. What is waiting keeps its place in the
      // buffer, which has a cap of its own and sheds the least valuable
      // sample first.
      persist();
      return;
    }
    var el = document.querySelector(COLLECTOR_TAG);
    if (!el || !el.$server || typeof el.$server.recordSamples !== 'function') {
      // Nothing to send through yet, and this may be the last code to run in
      // this tab: leave the buffer where the next load can find it.
      persist();
      return;
    }
    var batch = priorityFirst(buffer.splice(0, buffer.length));
    batch.forEach(function (sample) {
      // Measured here, and measured in offline time: the field says the
      // browser could not reach the server while this was waiting, so it
      // counts only the time that was true. Measured on this clock because
      // the browser's and the server's are not the same clock, and an
      // arrival-time subtraction would report skew rather than delay.
      sample.ageMs = bufferedMs(sample);
    });
    inFlight = batch;
    // Written before the send, and cleared only by settle(): between these two
    // lines the persisted copy is the only record that the batch existed.
    persist();
    try {
      var sent = el.$server.recordSamples(batch);
      var answered = function () {
        // The server either recorded the batch or answered that it could not.
        // Both mean it saw the batch, so resending would double-count what it
        // did record. A message Flow never delivered does not answer here at
        // all: Flow re-sends those itself, and the persisted copy covers the
        // tab that does not live to see it.
        settle(batch, false);
      };
      if (sent && typeof sent.then === 'function') {
        sent.then(answered, answered);
      } else {
        // No promise to wait on; treat the queued call as delivered.
        answered();
      }
    } catch (e) {
      // The call never entered Flow's message queue, so nothing was sent and
      // requeueing cannot double-count.
      settle(batch, true);
    }
  }

  // Releases the in-flight slot, because the server answered for the batch or
  // because it never left, and brings storage back in line with what is
  // still pending.
  function settle(batch, requeue) {
    if (inFlight !== batch) {
      return;
    }
    inFlight = null;
    if (requeue) {
      // From the tail again: batch is priority-ordered, so its head is what
      // the next flush must still be carrying.
      buffer = batch.concat(buffer).slice(0, BUFFER_MAX);
    }
    persist();
  }

  restore();

  // Bootstrap timing.
  try {
    var navEntries = performance.getEntriesByType('navigation');
    if (navEntries && navEntries.length > 0) {
      var nav = navEntries[0];
      var dur =
        nav.loadEventEnd > 0 ? nav.loadEventEnd - nav.fetchStart : nav.domContentLoadedEventEnd - nav.fetchStart;
      if (dur > 0) {
        pushSample('vaadin.client.bootstrap.duration', { route: currentRoute() }, dur);
      }
    }
  } catch (e) {
    /* ignore */
  }

  // Web Vitals: LCP.
  try {
    var lcpObserver = new PerformanceObserver(function (list) {
      var entries = list.getEntries();
      var last = entries[entries.length - 1];
      if (last) {
        var value = last.renderTime || last.loadTime || last.startTime;
        pushSample('vaadin.client.web_vitals.lcp', { route: currentRoute() }, value);
      }
    });
    lcpObserver.observe({ type: 'largest-contentful-paint', buffered: true });
  } catch (e) {
    /* unsupported, skip */
  }

  // Web Vitals: FCP (from paint timing).
  try {
    var paintObserver = new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (entry) {
        if (entry.name === 'first-contentful-paint') {
          pushSample('vaadin.client.web_vitals.fcp', { route: currentRoute() }, entry.startTime);
        }
      });
    });
    paintObserver.observe({ type: 'paint', buffered: true });
  } catch (e) {
    /* unsupported, skip */
  }

  // Errors. The counter is tagged only by kind; everything that identifies
  // *this* error goes in `detail`, which the server keeps as an insight and
  // never turns into a tag -- one time series per distinct message is not a
  // metric.

  // The server truncates these again on the way into the insight, but it is
  // the buffer and the request that need the cap: a message is whatever the
  // page threw, and 200 of them sit in sessionStorage across a whole outage
  // before anything server-side gets a say.
  //
  // The same number as StackFrames.MAX_LENGTH, and it has to stay that way
  // for a frame: a location is recognized by the :line that truncation
  // removes, so a line between the two caps would be one this collector
  // vetted and sent and the server then silently discarded.
  var DETAIL_MAX = 400;

  function detailText(value) {
    var text = String(value);
    return text.length > DETAIL_MAX ? text.slice(0, DETAIL_MAX) : text;
  }

  // Whether the application asked for error messages to be collected. Set by
  // the server ahead of this script. The message is gathered only when it is
  // on, rather than gathered and discarded later: a browser error message can
  // quote whatever the page was working with, and buffering one puts it in
  // sessionStorage and then on the wire, neither of which a server-side
  // retention rule can undo. The server applies the same rule again, for the
  // page that was already open when the setting changed.
  function detailsEnabled() {
    return window.__vaadinMicrometerDetails === true;
  }


  // Pulls the script location out of a line a browser wrote. This is a filter
  // on untrusted text: a location is published whatever the message policy
  // says, and a browser offers plenty that is not one -- V8 opens a stack with
  // the message, a message can span lines and a continuation line can look
  // like a URL, and `stack` on a rejection reason is whatever the rejecting
  // code put there.
  //
  // Split structurally, then validate the location. One regex over the whole
  // line cannot do it: the free text and the location sit in one expression,
  // so tightening one end loosens the other, and the alternation needed for
  // both engines backtracks super-quadratically on text built to fail.
  // Splitting on the separators the engines emit -- " ("…")" for V8, `@` for
  // SpiderMonkey and JSC -- puts the location in a slot of its own.
  //
  // The function name is *not* validated, because it cannot be: a page can set
  // any function's name to any string and V8 prints it, so there is no rule
  // that admits Object.<anonymous>, new Foo, async Foo.bar, Array.map
  // [as forEach] and Firefox's promise callback*fn without also admitting some
  // arrangement of words that is not a name. It travels as its own field,
  // gathered only when the server asked for detail -- like the message, and
  // for the same reason.
  //
  // Kept in step with StackFrames.java, which applies the same rules again.
  // This copy spares the buffer and the wire; it decides nothing, because any
  // script on the page can call the @ClientCallable directly. The corpus both
  // are checked against is one shared file, stack-frames-corpus.tsv.

  // The characters a file path or URL is made of: the unreserved and reserved
  // sets of RFC 3986, plus a Windows backslash, the braces and pipe a bundler
  // puts in a virtual path, and any letter or digit so a non-ASCII path
  // segment or an internationalized host survives. Note what is absent:
  // whitespace of every kind, and the parentheses stripped structurally
  // above. Deliberately not `\S`, which means one thing here and another in
  // Java, and excludes U+200B and U+FEFF in both.
  var FILE = /^[\p{L}\p{N}._~:/?#!$&'*+,;=%@[\]{}|\\-]+$/u;

  // What a whole name@location line may be made of: the FILE characters plus
  // the angle brackets Firefox writes into a nested-function name (name/<@…).
  // The structural rule the @ form needs and V8's does not: V8 delimits its
  // location, so the split point is the engine's choice, while the @ form's
  // split lands wherever an @ happens to be -- which cuts a sentence into a
  // "name" and something that passes for a location. Requiring the whole line
  // to be built from these characters separates a frame line from a sentence,
  // because a sentence has spaces and none of these is one.
  //
  // The cost, deliberately paid: Safari's "global code@…", "module code@…",
  // "eval code@…" and Firefox's space-containing async markers are no longer
  // read as frames. firstFrame moves to the next line, and where such a line
  // is the only one the insight falls back to `source`.
  var AT_FORM_LINE = /^[\p{L}\p{N}._~:/?#!$&'*+,;=%@[\]{}|\\<>-]+$/u;

  // Longest run of digits a line or column number may be. A real one is a
  // position in a file; a longer one is a number that wanted to be published.
  var MAX_NUMBER_DIGITS = 8;

  // numberStart found no `:digits` run at all.
  var NO_NUMBER = -1;
  // numberStart found one longer than a position in a file ever is. Distinct
  // from NO_NUMBER because a location legitimately has no column, while a slot
  // that failed the cap must reject the text rather than be folded back into
  // the file -- FILE admits digits and colons, so "/a.js:4111111111111111:1"
  // would otherwise pass with the over-long run inside its "file".
  var OVER_LONG = -2;

  // The index of the ':' introducing the run of digits ending at `end`.
  // ASCII 0-9 only, matching the server's copy character for character: Java's
  // Character.isDigit would take every Unicode decimal digit, and a server
  // that accepts what the browser rejects is the disagreement FILE exists to
  // avoid.
  function numberStart(text, end) {
    var at = end;
    while (at > 0 && text.charCodeAt(at - 1) >= 48 && text.charCodeAt(at - 1) <= 57) {
      at--;
      if (end - at > MAX_NUMBER_DIGITS) {
        return OVER_LONG;
      }
    }
    return at < end && at > 0 && text.charAt(at - 1) === ':' ? at - 1 : NO_NUMBER;
  }

  // Whether the text is file:line or file:line:col with a file that looks like
  // one. Parsed from the end by hand rather than by regex: the file may
  // contain colons and digits of its own -- every http:// does -- so a pattern
  // would have to guess where the file stops, and guessing is what makes one
  // backtrack.
  function isLocation(text) {
    var lastNumber = numberStart(text, text.length);
    if (lastNumber < 0) {
      return false;
    }
    var firstNumber = numberStart(text, lastNumber);
    // An over-long run has to fail the location, not fall through to being
    // part of the file: appending a column is otherwise all it takes to get an
    // arbitrary number published inside the "file".
    if (firstNumber === OVER_LONG) {
      return false;
    }
    var file = text.slice(0, firstNumber === NO_NUMBER ? lastNumber : firstNumber);
    // The file may not itself end in ":digits". A location has a line and at
    // most a column and nothing after them, so this bounds the numeric slots
    // to the two just checked rather than the last two of however many there
    // are: a third number would otherwise push an over-long run back into the
    // "file", where FILE accepts it. A port survives, since
    // "http://host:8080/app.js" does not end in a number.
    if (numberStart(file, file.length) !== NO_NUMBER) {
      return false;
    }
    // A location names a file, and a file has an extension or a path
    // separator. Without this, any "word:1" would be one.
    return (
      (file.indexOf('.') >= 0 || file.indexOf('/') >= 0) &&
      FILE.test(file) &&
      separatorIn(file) < 0 &&
      !hasUserInfo(file)
    );
  }

  // A URL scheme and its `://`, matched at the start of the text. What tells a
  // path prefix from a function name, together with a leading slash.
  var SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*:\/\//;

  // Whether the @ at `at` belongs to the path rather than separating a name
  // from one. The question is asked of the text *before* the @, not of the
  // single character in front of it: "previous character is a slash" covers a
  // scoped package at the start of a segment (/npm/@vaadin/…) and nothing
  // else, so it rejected every version-pinned CDN URL --
  // …/@vaadin/router@1.7.5/dist/router.js -- and an app on jsDelivr, unpkg or
  // esm.sh lost frame and source for every error it reported.
  //
  // A path prefix is rooted or schemed: /node_modules/…, https://…,
  // webpack://…. A function name is neither, and this is deliberately
  // narrower than "the prefix contains a slash" -- Firefox writes a nested
  // function as outer/inner and an anonymous one as outer/<, so a slash alone
  // would make those look like paths and publish the name glued on.
  //
  // The leading @ is its own case: Firefox writes one for a frame with no
  // function, so it separates when a location plainly follows. When anything
  // else follows it opens a scoped bare specifier, @vaadin/grid/x.js, and
  // eating the @ names a package that does not exist.
  function partOfPath(text, at) {
    if (at === 0) {
      var rest = text.slice(1);
      return !(rest.charAt(0) === '/' || SCHEME.test(rest));
    }
    var prefix = text.slice(0, at);
    return prefix.indexOf('/') >= 0 && (prefix.charAt(0) === '/' || SCHEME.test(prefix));
  }

  // The index of the first @ that is not part of a path, or -1 when every @ in
  // the text is. Used both ways round: isLocation requires there to be no such
  // @, so a whole name@file:line:col line is not mistaken for a location;
  // parseFrame takes it as the split point, so the same line is taken apart.
  function separatorIn(text) {
    for (var at = text.indexOf('@'); at >= 0; at = text.indexOf('@', at + 1)) {
      if (!partOfPath(text, at)) {
        return at;
      }
    }
    return -1;
  }

  // Whether a URL carries credentials -- an @ inside its authority with
  // something in front of it. A rule of its own rather than folded into
  // partOfPath, because a version pin and a password share nothing but the
  // character: one is in the path, the other between the scheme and the first
  // slash. Refused because a location with a password in it should not travel
  // in a forwarded payload, and a browser does not load subresources from
  // userinfo URLs, so nothing real is lost. The empty-userinfo form is not
  // that: webpack://@scope/pkg/… has to survive.
  function hasUserInfo(file) {
    var scheme = SCHEME.exec(file);
    if (scheme === null) {
      return false;
    }
    var slash = file.indexOf('/', scheme[0].length);
    var authority = slash < 0 ? file.slice(scheme[0].length) : file.slice(scheme[0].length, slash);
    return authority.indexOf('@') > 0;
  }

  // Whether the text ends in both a line and a column number.
  function hasLineAndColumn(text) {
    var column = numberStart(text, text.length);
    return column >= 0 && numberStart(text, column) >= 0;
  }

  // One stack line taken apart: { location, function } when it names a
  // location, null when it does not. `function` is whatever stood in front of
  // the location, unexamined.
  function parseFrame(line) {
    var fn = null;
    var location;
    if (line.indexOf('at ') === 0) {
      var rest = line.slice(3);
      var open = rest.lastIndexOf(' (');
      if (open >= 0 && rest.charAt(rest.length - 1) === ')') {
        fn = rest.slice(0, open);
        location = rest.slice(open + 2, rest.length - 1);
      } else {
        // V8's unnamed frame: "at file:line:col".
        location = rest;
      }
    } else {
      // The line as a whole has to look like one before its @ may be treated
      // as a separator at all -- see AT_FORM_LINE. Without this, any sentence
      // containing an @ splits into a "name" and something that passes for a
      // location, and since firstFrame takes the first line that parses and a
      // message precedes the stack, the report loses the real frame as well as
      // gaining a host.
      if (!AT_FORM_LINE.test(line)) {
        return null;
      }
      // Firefox, SpiderMonkey and JSC all write file:line:column here.
      // Requiring both numbers is the second half of telling a frame line from
      // a URL that merely ends in a number -- a message quoting
      // "…/q3-report.pdf:1" has one.
      if (!hasLineAndColumn(line)) {
        return null;
      }
      // The first @ that is not part of a path -- see separatorIn. An @ after
      // a slash belongs to a scoped package or a virtual path, so splitting
      // there would cut a path in half. Firefox writes a bare @ for a
      // top-level frame, which leaves the name empty.
      var at = separatorIn(line);
      if (at < 0) {
        return null;
      }
      fn = at === 0 ? null : line.slice(0, at);
      location = line.slice(at + 1);
      // A colon before the @ means the split landed inside a URL rather than
      // at a name boundary: no name an engine writes has a colon in it.
      if (fn !== null && fn.indexOf(':') >= 0) {
        return null;
      }
    }
    if (!isLocation(location)) {
      return null;
    }
    // "at  (/a.js:1:2)" -- a V8 frame whose name is absent rather than empty.
    return { location: location, fn: fn === null || fn.trim() === '' ? null : fn };
  }

  // The first line of the stack that is a frame. Nothing else in a stack may
  // be used, and the search does not stop at the first line that merely looks
  // location-ish: a message continuation line above the real frame would
  // otherwise be preferred over it.
  //
  // Capped before it is inspected: a line is as long as whatever built the
  // stack made it.
  function firstFrame(error) {
    if (!error || !error.stack) {
      return null;
    }
    var lines = String(error.stack).split('\n');
    for (var i = 0; i < lines.length; i++) {
      var frame = parseFrame(detailText(lines[i].trim()));
      if (frame) {
        return frame;
      }
    }
    return null;
  }

  // The description of one browser error, gathered a field at a time because
  // each field can raise: `reason` may be an object with no prototype or a
  // throwing toString, and `message` and `stack` may be getters that fail.
  // Whatever is gathered stands, and none of it may cost the count -- the one
  // part of a report that always works.
  function errorDetail(event, error, fallbackMessage) {
    var detail = {};
    try {
      detail.route = currentRoute();
    } catch (e) {
      /* ignore */
    }
    try {
      // Only a real script URL, and only from the browser. When there is none
      // -- a cross-origin script reports "Script error." with no filename, and
      // a rejection has no filename at all -- the page's own path is not a
      // stand-in: it is not where the code is, and it carries the ids that
      // route templating exists to fold away, which would split one bug into
      // one finding per order id.
      var file = event && event.filename;
      if (file) {
        detail.source = detailText(file + ':' + ((event && event.lineno) || 0));
      }
    } catch (e) {
      /* ignore */
    }
    var frame = null;
    try {
      frame = firstFrame(error);
      if (frame) {
        detail.frame = frame.location;
      }
    } catch (e) {
      /* ignore */
    }
    if (detailsEnabled()) {
      try {
        detail.message = detailText(
          (event && event.message) || (error && error.message) || error || fallbackMessage
        );
      } catch (e) {
        /* ignore */
      }
      // The function name goes under the same gate as the message, since it is
      // a string the page chose just as surely.
      if (frame && frame.fn) {
        detail.function = frame.fn;
      }
    }
    return detail;
  }

  window.addEventListener('error', function (event) {
    pushSample(CLIENT_ERRORS, { kind: 'uncaught' }, 0, errorDetail(event, event && event.error, 'Error'));
  });
  window.addEventListener('unhandledrejection', function (event) {
    // No event fields to read: a rejection carries only its reason.
    pushSample(CLIENT_ERRORS, { kind: 'promise' }, 0, errorDetail(null, event && event.reason, 'Unhandled rejection'));
  });

  // Connection state. Flow's client already keeps this in
  // window.Vaadin.connectionState; without subscribing to it, a user losing the
  // server and coming back is invisible to every meter -- the server only sees
  // a session that goes quiet and then talks again.
  try {
    var store = connectionStore();
    if (store && typeof store.addStateChangeListener === 'function') {
      // The last state that was not LOADING. Transitions are reported against
      // this rather than against the previous state the store hands us, so the
      // loading round trip of every interaction collapses to nothing and a
      // failed retry during an outage does not report a second loss.
      lastState = normalizeState(store.state);
      // When the state being left began. An outage is timed per state rather
      // than end to end, because RECONNECTING and CONNECTION_LOST mean
      // different things: Flow enters RECONNECTING on the first failure and
      // only reaches CONNECTION_LOST once it has given up retrying, so the two
      // separate "the network hiccuped" from "the server went away". Summing
      // the two gives the whole outage back.
      var stateSince = isOfflineState(lastState) ? monotonicNow() : null;
      // The offline clock starts here too, and unlike stateSince it does not
      // restart when one offline state becomes another: RECONNECTING becoming
      // CONNECTION_LOST is the same outage continuing, and a report held
      // across both waited out all of it.
      offlineSince = stateSince;

      store.addStateChangeListener(function (previous, current) {
        if (isLoading(current)) {
          // A request starting, not a connection event.
          return;
        }
        var to = normalizeState(current);
        if (to === lastState) {
          return;
        }
        var from = lastState;
        lastState = to;
        var now = monotonicNow();
        // Before anything is pushed, so the transition out of an outage is
        // itself stamped with the outage over: the sample that says the
        // server came back did not wait for it.
        if (isOfflineState(to)) {
          if (offlineSince === null) {
            offlineSince = now;
          }
        } else if (offlineSince !== null) {
          offlineMs += Math.max(0, now - offlineSince);
          offlineSince = null;
        }
        pushSample(CONNECTION, { state: to }, 0);
        if (isOfflineState(from) && stateSince !== null) {
          // Timed on this clock end to end, for the same reason ageMs is.
          pushSample(CONNECTION_DOWNTIME, { state: from }, now - stateSince);
        }
        stateSince = isOfflineState(to) ? now : null;
        if (!isOfflineState(to)) {
          // Back in contact: send what the outage produced, including the
          // transition that started it.
          flush();
        }
      });
    }
  } catch (e) {
    /* store unavailable, skip */
  }

  // Navigation timing: observe history changes.
  var navStart = null;
  function startNav() {
    navStart = performance.now();
  }
  function endNav(trigger) {
    if (navStart === null) {
      return;
    }
    var duration = performance.now() - navStart;
    navStart = null;
    pushSample('vaadin.client.navigation.duration', { route: currentRoute(), trigger: trigger }, duration);
  }
  try {
    window.addEventListener('popstate', function () {
      startNav();
      // navigation completes by the next animation frame typically; the
      // route path is already updated by the time popstate fires.
      requestAnimationFrame(function () {
        endNav('back');
      });
    });
    // Wrap pushState / replaceState to detect programmatic navigation.
    ['pushState', 'replaceState'].forEach(function (op) {
      var orig = history[op];
      history[op] = function () {
        startNav();
        var result = orig.apply(this, arguments);
        requestAnimationFrame(function () {
          endNav('programmatic');
        });
        return result;
      };
    });
  } catch (e) {
    /* ignore */
  }

  // Periodic flush.
  setInterval(flush, FLUSH_INTERVAL_MS);

  // Leaving the page: flush what we can. flush() hands the batch to storage
  // before it sends and clears it only once the server has answered, so a tab
  // closed mid-outage -- or mid-send -- still reports on its next load.
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') {
      flush();
    }
  });
  window.addEventListener('pagehide', function () {
    flush();
  });

  // Expose for tests / dashboards (debug only).
  window.__vaadinMicrometer = {
    flush: flush,
    bufferSize: function () {
      return buffer.length;
    }
  };
})();
