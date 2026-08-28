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

  // The last connection state that was not LOADING, maintained by the
  // connection listener below and read by the flush guard. Undefined until the
  // store is found, which reads as online -- the behaviour when a page has no
  // store at all.
  var lastState;

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

  function pushSample(name, tags, valueMs) {
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

  // The buffer, held where a reload can find it again. Best-effort: storage is
  // unavailable in some privacy modes and full in others, and neither is worth
  // failing a measurement over.
  function persist() {
    try {
      if (buffer.length === 0) {
        window.sessionStorage.removeItem(STORAGE_KEY);
      } else {
        window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(buffer));
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
      // Oldest first, ahead of anything this page load has produced; their age
      // keeps growing from the ts the previous load stamped them with.
      // Truncated from the tail, matching the send priority: the head of a
      // persisted buffer is the transition that started the outage.
      buffer = samples.concat(buffer).slice(0, BUFFER_MAX);
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
  // 1 -- everything else. A lost bootstrap, navigation or vitals sample is a
  //      gap in a distribution that the next page load refills.
  function priority(sample) {
    return sample.name === CONNECTION || sample.name === CONNECTION_DOWNTIME
      ? 0
      : 1;
  }

  /** Stable ordering by priority, so the head of a batch is what must survive. */
  function priorityFirst(batch) {
    var buckets = [[], []];
    batch.forEach(function (sample) {
      buckets[priority(sample)].push(sample);
    });
    return buckets[0].concat(buckets[1]);
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
    var el = document.querySelector(COLLECTOR_TAG);
    if (!el || !el.$server || typeof el.$server.recordSamples !== 'function') {
      return;
    }
    var now = Date.now();
    var batch = priorityFirst(buffer.splice(0, buffer.length));
    batch.forEach(function (sample) {
      // Measured here, on the clock that timestamped it: the browser's clock
      // and the server's are not the same clock, so an arrival-time
      // subtraction on the server would report skew rather than delay.
      sample.ageMs = Math.max(0, now - sample.ts);
    });
    try {
      el.$server.recordSamples(batch);
      persist();
    } catch (e) {
      // The call never entered Flow's message queue, so nothing was sent and
      // requeueing cannot double-count. A rejection *after* it was queued is
      // left alone: Flow re-sends pending messages itself.
      // From the tail again: batch is priority-ordered, so its head is what
      // the next flush must still be carrying.
      buffer = batch.concat(buffer).slice(0, BUFFER_MAX);
      persist();
    }
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

  // Errors.
  window.addEventListener('error', function () {
    pushSample(CLIENT_ERRORS, { kind: 'uncaught' }, 0);
  });
  window.addEventListener('unhandledrejection', function () {
    pushSample(CLIENT_ERRORS, { kind: 'promise' }, 0);
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
      var stateSince = isOfflineState(lastState) ? Date.now() : null;

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
        var now = Date.now();
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

  // Leaving the page: flush if we can, and hand the buffer to storage if we
  // cannot, so a tab closed mid-outage still reports on its next load.
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') {
      flush();
      persist();
    }
  });
  window.addEventListener('pagehide', function () {
    flush();
    persist();
  });

  // Expose for tests / dashboards (debug only).
  window.__vaadinMicrometer = {
    flush: flush,
    bufferSize: function () {
      return buffer.length;
    }
  };
})();
