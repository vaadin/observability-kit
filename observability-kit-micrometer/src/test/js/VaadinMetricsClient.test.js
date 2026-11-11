// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Runs the real in-browser collector against a stubbed browser, on a clock
// this file controls. Covers what the server-side tests cannot reach: which
// wait ends up in ageMs, what the error listeners put in `detail`, and what
// they do with a reason that fights being read.
//
//   node observability-kit-micrometer/src/test/js/VaadinMetricsClient.test.js
//
// No dependencies and no runner: the module has no JavaScript build, and this
// is deliberately something `node` alone can execute. Exits non-zero on
// failure, and `mvn test` runs it that way (exec-maven-plugin, bound to the
// test phase in the module's pom) -- the location grammar lives in two
// languages, and nothing but this file checks the browser copy, so a one-sided
// change to it has to fail a build rather than wait for someone to run this by
// hand. Skipped with -Dskip.js.tests when node is not on PATH.
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(
  path.join(__dirname, '../../main/resources/META-INF/frontend/VaadinMetricsClient.js'),
  'utf8'
);

// Loading the module only defines the collector element; the stand-in for the
// browser connects one as soon as it is defined, which is what attaching it
// from the server does.
global.HTMLElement = class {};
global.customElements = {
  get: () => undefined,
  define: (tag, type) => new type().connectedCallback()
};

// The frame corpus, read from the same file StackFramesTest reads, so that
// "the two copies of this rule agree" is enforced rather than asserted.
const CORPUS = path.join(__dirname, '../resources/stack-frames-corpus.tsv');
const corpus = fs
  .readFileSync(CORPUS, 'utf8')
  .split('\n')
  // The line ending comes off before the tab split: a CRLF checkout would
  // otherwise leave a \r on the last column, failing every row with a
  // difference that does not show up on screen.
  .map((line) => (line.endsWith('\r') ? line.slice(0, -1) : line))
  .filter((line) => line.trim() !== '' && !line.startsWith('#'))
  .map((line) => {
    const columns = line.split('\t');
    // Only `keep` rows are stack lines. A `loc` row is a bare location, which
    // parseFrame must refuse just as StackFrames.parse does -- the server's
    // frame() is what accepts those, and it has no counterpart here.
    return { verdict: columns[0], line: columns[1], location: columns[2] };
  });

// parseFrame and isLocation live inside installCollector; reach them by
// evaluating its body, after the declarations above it, with a probe appended,
// in a throwaway environment separate from the one below. Both are needed:
// parseFrame is the stack-line rule, isLocation the rule applied to `source`
// and to a bare `frame`, and the corpus has rows that only one of the two
// decides.
function frameRules() {
  const head = src.slice(0, src.indexOf('function installCollector() {'));
  const open = src.indexOf('function installCollector() {');
  const close = src.indexOf('\n}\n\nif (!customElements');
  const body = head + src.slice(open + 'function installCollector() {'.length, close);
  return new Function(
    'window',
    'document',
    'performance',
    'PerformanceObserver',
    'history',
    'setInterval',
    'requestAnimationFrame',
    body + '\n; return { parseFrame: parseFrame, isLocation: isLocation };'
  )(
    { addEventListener() {}, location: { pathname: '/' }, sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} } },
    { querySelector: () => null, addEventListener() {} },
    { getEntriesByType: () => [], now: () => 0 },
    function () { throw new Error('unsupported'); },
    {},
    () => 0,
    () => 0
  );
}

let clock = 1000000;
Date.now = () => clock;

const listeners = {};
const add = (name, cb) => { (listeners[name] = listeners[name] || []).push(cb); };
const store = {
  state: 'connected',
  cbs: [],
  addStateChangeListener(cb) { this.cbs.push(cb); },
  go(to) { const prev = this.state; this.state = to; this.cbs.forEach((cb) => cb(prev, to)); }
};

let sent = [];
// `details` stands for the attribute the server sets when error messages are
// to be gathered.
const collector = { details: true, hasAttribute(name) { return name === 'details' && this.details; }, $server: { recordSamples: (batch) => { sent.push(batch); return Promise.resolve(); } } };

global.window = {
  addEventListener: add,
  // href as well as pathname: a browser reports the whole document URL as the
  // filename of an error thrown from inline code, and the query string is
  // exactly what must not be published.
  location: { pathname: '/orders/17', href: 'https://app.example.com/orders/17?token=abc123' },
  sessionStorage: { store: {}, getItem(k) { return this.store[k] || null; }, setItem(k, v) { this.store[k] = v; }, removeItem(k) { delete this.store[k]; } },
  Vaadin: { connectionState: store }
};
global.document = { querySelector: (s) => (s === 'vaadin-metrics-collector' ? collector : null), addEventListener: add, visibilityState: 'visible' };
global.performance = { getEntriesByType: () => [], now: () => clock };
global.PerformanceObserver = function () { throw new Error('unsupported'); };
global.history = {};
global.setInterval = () => 0;
global.requestAnimationFrame = () => 0;

new Function('window', 'document', 'performance', 'PerformanceObserver', 'history', 'setInterval', 'requestAnimationFrame', src)(
  global.window, global.document, global.performance, global.PerformanceObserver, global.history, global.setInterval, global.requestAnimationFrame
);

const fire = (name, event) => (listeners[name] || []).forEach((cb) => cb(event));
const tick = () => new Promise((r) => setImmediate(r));

let failures = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `\n        got ${JSON.stringify(actual)} want ${JSON.stringify(expected)}`}`);
}

// A recovery is what flushes; drain the answer so the next one can go out.
async function recoverAndFlush() {
  store.go('connection-lost');
  store.go('connected');
  await tick();
  const batch = sent.flat().filter((s) => s.name === 'vaadin.client.errors');
  sent = [];
  return batch;
}

// A second collector in an environment of its own, installed over a
// sessionStorage that already holds `stored`. The one below has already run
// its restore() by the time any test can seed storage, so this is the only way
// to ask what a restore makes of what it finds. Returns the fake window, whose
// `handlers` hold the listeners the collector installed.
function freshCollector(stored) {
  const win = {
    handlers: {},
    addEventListener(name, cb) {
      (this.handlers[name] = this.handlers[name] || []).push(cb);
    },
    location: { pathname: '/x', href: 'https://app.example.com/x' },
    sessionStorage: {
      store: { 'vaadin.observability.buffer': stored },
      getItem(k) { return this.store[k] === undefined ? null : this.store[k]; },
      setItem(k, v) { this.store[k] = v; },
      removeItem(k) { delete this.store[k]; }
    }
  };
  new Function('window', 'document', 'performance', 'PerformanceObserver', 'history', 'setInterval', 'requestAnimationFrame', src)(
    win,
    { querySelector: () => null, addEventListener() {}, visibilityState: 'visible' },
    { getEntriesByType: () => [], now: () => 0 },
    function () { throw new Error('unsupported'); },
    {},
    () => 0,
    () => 0
  );
  return win;
}

function err(message, stack) {
  const e = new Error(message);
  if (stack !== undefined) { e.stack = stack; }
  return e;
}

(async () => {
  // 1. A routine error waits for the flush interval but claims no outage.
  fire('error', { message: 'boom', filename: '/VAADIN/build/chart.js', lineno: 44, error: err('boom') });
  clock += 5000;
  let batch = await recoverAndFlush();
  check('routine error reports ageMs 0 after a 5 s wait', batch.map((s) => s.ageMs), [0]);

  // 2. An error raised during an outage reports the outage, across both
  //    offline states, and the recovery sample itself reports nothing.
  store.go('connection-lost');
  clock += 100;
  fire('error', { message: 'offline boom', filename: '/app.js', lineno: 9, error: err('offline boom') });
  clock += 7400;
  store.go('reconnecting');
  clock += 600;
  store.go('connected');
  await tick();
  let all = sent.flat();
  sent = [];
  check('error raised in an outage reports the offline time it waited',
    all.filter((s) => s.name === 'vaadin.client.errors').map((s) => s.ageMs), [8000]);
  check('the recovery transition itself waited for nothing',
    all.filter((s) => s.name === 'vaadin.client.connection' && s.tags.state === 'connected').map((s) => s.ageMs), [0]);

  // 3. An error after an earlier outage still reports 0.
  clock += 30000;
  fire('error', { message: 'later', filename: '/app.js', lineno: 1, error: err('later') });
  clock += 4000;
  batch = await recoverAndFlush();
  check('an error after an earlier outage still reports 0', batch.map((s) => s.ageMs), [0]);

  // 4. A reason that cannot be stringified must not cost the count.
  fire('unhandledrejection', { reason: Object.create(null) });
  fire('unhandledrejection', { reason: { get message() { throw new Error('nope'); }, get stack() { throw new Error('nope'); } } });
  batch = await recoverAndFlush();
  check('a hostile rejection reason still produces two counts', batch.length, 2);

  // 5. A stack with no frames must not leak the message line.
  fire('error', { message: 'declined', filename: '', lineno: 0, error: err('card 4111 declined', 'Error: card 4111 declined') });
  fire('error', { message: 'failed', filename: '', lineno: 0, error: err('failed\nat startup', 'Error: failed\nat startup') });
  batch = await recoverAndFlush();
  check('a frameless stack yields no frame', batch.map((s) => s.detail.frame), [null, null]);
  check('a cross-origin error reports no source rather than the page path', batch.map((s) => s.detail.source), [null, null]);
  check('nothing in the batch quotes the card number',
    JSON.stringify(batch).includes('4111'), false);

  // 5b. The frame rule, over the shared corpus. Every line StackFramesTest
  //     checks on the server is checked here too, against the same expected
  //     location -- that is what makes "the two copies agree" a fact rather
  //     than a sentence in a javadoc.
  {
    const rules = frameRules();
    let mismatches = 0;
    for (const entry of corpus) {
      const frame = rules.parseFrame(entry.line);
      const got = frame === null ? null : frame.location;
      const want = entry.verdict === 'keep' ? entry.location : null;
      if (got !== want) {
        mismatches++;
        console.log(
          `FAIL  corpus ${JSON.stringify(entry.line)}\n        got ${JSON.stringify(got)} want ${JSON.stringify(want)}`
        );
      }
    }
    check(`the shared corpus (${corpus.length} lines) agrees with the server`, mismatches, 0);

    // The other half of the same rule, and the half the loop above cannot
    // see: a `loc` row is a bare location, which parseFrame refuses by
    // definition, so every row asserting what may be published as `source` --
    // a credentialed URL, a data: payload -- used to be checked on the server
    // alone. isLocation is what the browser decides those with.
    let wrongVerdicts = 0;
    for (const entry of corpus) {
      const want = entry.verdict === 'loc';
      if (rules.isLocation(entry.line) !== want) {
        wrongVerdicts++;
        console.log(`FAIL  corpus location rule ${JSON.stringify(entry.line)}\n        want ${want}`);
      }
    }
    check('and the location rule agrees on every row too', wrongVerdicts, 0);
  }

  // 5b'''. The function name travels separately, and only under the gate.
  {
    const stack = 'Error: boom\n    at handleCardNumber4111 (chart.js:44:13)';
    collector.details = true;
    fire('error', { message: 'boom', filename: '', lineno: 0, error: err('boom', stack) });
    let one = (await recoverAndFlush())[0];
    check('the location is the frame', one.detail.frame, 'chart.js:44:13');
    check('the name is its own field when detail is on', one.detail.function, 'handleCardNumber4111');

    collector.details = false;
    fire('error', { message: 'boom', filename: '', lineno: 0, error: err('boom', stack) });
    one = (await recoverAndFlush())[0];
    check('the location is published either way', one.detail.frame, 'chart.js:44:13');
    check('the name is not gathered when detail is off', one.detail.function, undefined);
    collector.details = true;
  }

  // 5b'. Separators that are whitespace to one engine and not the other.
  //      ZWSP and BOM are whitespace to neither, which is why the rule is a
  //      positive class rather than a `\S` the two languages disagree about.
  for (const [name, blank] of Object.entries({
    NBSP: ' ',
    FIGSP: ' ',
    NNBSP: ' ',
    IDEOSP: '　',
    ZWSP: '​',
    BOM: '﻿'
  })) {
    for (const text of [
      `at f (/x/${blank}card${blank}number${blank}4111${blank}declined.x:1:2)`,
      `Error:${blank}failed${blank}to${blank}fetch${blank}https://user@api.example.com:8443`,
      `token${blank}abc${blank}4111@evil.example.com/x.js:1:2`
    ]) {
      fire('error', { message: 'x', filename: '', lineno: 0, error: err('x', text) });
      const one = (await recoverAndFlush())[0];
      // Asserting nothing at all comes back, not merely that the payload's
      // words did not survive. The weaker assertion passed while a non-frame
      // line was being parsed as one and displacing the real frame.
      check(`${name} is not a frame line`, one.detail.frame, undefined);
    }
  }

  // 5b''. The multi-line message leak: V8 opens a stack with the message,
  //       a message can span lines, and a continuation line that looks like a
  //       URL must not outrank the real frame one line below it.
  {
    const stack =
      'Error: Upload failed for:\n' +
      '     https://alice@files.example.com/private/q3-report.pdf:1\n' +
      '    at upload (/app/upload.js:9:5)';
    fire('error', { message: 'Upload failed', filename: '', lineno: 0, error: err('Upload failed', stack) });
    const one = (await recoverAndFlush())[0];
    check('the real frame is chosen over a message continuation line', one.detail.frame, '/app/upload.js:9:5');
    check('and the message URL is nowhere in the batch', JSON.stringify(one).includes('q3-report'), false);
  }

  // 5c. A line long enough to backtrack over is bounded before it is matched.
  {
    const hostile = 'at ' + 'a. '.repeat(400000) + 'x';
    const started = process.hrtime.bigint();
    fire('error', { message: 'x', filename: '', lineno: 0, error: err('x', hostile) });
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    const one = (await recoverAndFlush())[0];
    check('a megabyte of text is not a frame', one.detail.frame, undefined);
    check(`matching stays bounded (${ms.toFixed(0)} ms)`, ms < 1000, true);
  }

  // 5d. The wait a report has accrued has to reach sessionStorage, because
  //     the in-memory baselines die with the page and a page only loads while
  //     the server is reachable -- so a report surviving a crash mid-outage
  //     would otherwise come back reporting no outage at all.
  {
    store.go('connection-lost');
    fire('error', { message: 'mid-outage', filename: '/app.js', lineno: 3, error: err('mid-outage') });
    clock += 6100;
    fire('error', { message: 'nudge the persist', filename: '/app.js', lineno: 4, error: err('nudge') });
    const stored = JSON.parse(global.window.sessionStorage.store['vaadin.observability.buffer']);
    const held = stored.filter((s) => s.detail && s.detail.source === '/app.js:3');
    check('a persisted report carries the wait it has accrued', held.map((s) => s.ageMs), [6100]);
    await recoverAndFlush();
  }

  // 6. A real frame and a real script URL survive.
  fire('error', { message: 'boom', filename: '/VAADIN/build/chart.js', lineno: 44, error: err('boom', 'Error: boom\n    at renderChart (chart.js:44:13)') });
  batch = await recoverAndFlush();
  check('a real frame is reported, as its location', batch[0].detail.frame, 'chart.js:44:13');
  check('a real filename is reported as script:line', batch[0].detail.source, '/VAADIN/build/chart.js:44');
  check('the route travels as the browser path, for the server to template', batch[0].detail.route, '/orders/17');

  // 6b. The document URL is not a script location. Browsers report it as the
  //     filename for an error from an inline script, an inline handler or
  //     executeJs code -- the page's own path with its query string, which is
  //     published above the message gate.
  {
    fire('error', {
      message: 'boom',
      filename: global.window.location.href,
      lineno: 12,
      error: err('boom', 'Error: boom\n    at handler (/app/inline.js:1:1)')
    });
    const one = (await recoverAndFlush())[0];
    check('the document URL is not reported as a source', one.detail.source, undefined);
    check('and the page query string is nowhere in the report', JSON.stringify(one).includes('abc123'), false);
    check('the frame the stack named is still reported', one.detail.frame, '/app/inline.js:1:1');
  }

  // 6c. A stack line longer than the cap is skipped, not cut. A cut line can
  //     still parse -- the ":line:col" it ends in is what the cut removes --
  //     and then names a place that does not exist, or blames the frame below
  //     it. Long lines are real in dev: webpack virtual paths and Vite "?t="
  //     URLs go past 400 characters.
  {
    const path = '/x/' + 'a'.repeat(388) + '.js';
    const stack = 'Error: boom\n    at ' + path + ':1234:56\n    at g (/app/real.js:9:5)';
    fire('error', { message: 'boom', filename: '', lineno: 0, error: err('boom', stack) });
    const one = (await recoverAndFlush())[0];
    check('an over-long frame line is skipped rather than truncated', one.detail.frame, '/app/real.js:9:5');
  }

  // 6d. The message of a rejection is the only thing identifying it, so a
  //     falsy reason has to be reported rather than replaced by the generic
  //     fallback -- otherwise reject(0), reject(false) and an object reason
  //     all merge into one indistinguishable group.
  {
    fire('unhandledrejection', { reason: 0 });
    fire('unhandledrejection', { reason: '' });
    fire('unhandledrejection', { reason: false });
    fire('unhandledrejection', { reason: { code: 42 } });
    const batch = await recoverAndFlush();
    check(
      'a falsy or object rejection reason keeps its own message',
      batch.map((s) => s.detail.message),
      // The empty string is the one that legitimately has nothing to report:
      // an empty message and no message are the same claim.
      ['0', 'Unhandled rejection', 'false', '{"code":42}']
    );
  }

  // 6e. A lost answer must not stall the collector for the life of the tab.
  //     The $server promise settles on the server's answer, and an answer can
  //     be lost for good -- which is exactly the case a recovery flush is.
  {
    const answering = collector.$server.recordSamples;
    collector.$server.recordSamples = (batch) => {
      sent.push(batch);
      return new Promise(() => {});
    };
    fire('error', { message: 'stalled', filename: '/app.js', lineno: 1, error: err('stalled') });
    await recoverAndFlush();

    collector.$server.recordSamples = answering;
    fire('error', { message: 'behind it', filename: '/app.js', lineno: 2, error: err('behind it') });
    let batch = await recoverAndFlush();
    check('a batch is not re-sent while its answer may still be coming', batch.length, 0);

    clock += 31000;
    batch = await recoverAndFlush();
    check('past the deadline the batch is taken back and both reports go out', batch.length, 2);
  }

  // 6f. sessionStorage holds whatever is under the key: an older version's
  //     shape, another script's data. One primitive element used to throw
  //     after the buffer had been replaced and the stored copy removed, and
  //     every later push and flush threw on the poisoned array until reload.
  {
    const survivor = { name: 'vaadin.client.errors', tags: { kind: 'uncaught' }, valueMs: 0, ts: 1, ageMs: 0 };
    const poisoned = freshCollector(JSON.stringify([null, 'x', 7, survivor]));
    poisoned.handlers.error.forEach((cb) =>
      cb({ message: 'after the restore', filename: '/app.js', lineno: 1, error: err('after the restore') })
    );
    check('a poisoned stored buffer costs neither the restore nor the count', poisoned.__vaadinMicrometer.bufferSize(), 2);
  }

  // 7. The message gate, and what it keeps out of sessionStorage.
  collector.details = false;
  store.go('connection-lost');
  fire('error', { message: 'secret payload', filename: '/app.js', lineno: 1, error: err('secret payload') });
  check('nothing sensitive reaches sessionStorage while offline',
    JSON.stringify(global.window.sessionStorage.store).includes('secret payload'), false);
  batch = await recoverAndFlush();
  check('no message is gathered when details are off', batch.map((s) => s.detail.message), [null]);

  // 8. Interaction timing, from the request and message events Flow's client
  //    announces on window.Vaadin.Flow.clients[id].events. The client here is
  //    a stand-in that dispatches them by hand, as Flow does: a request is
  //    announced when the first invocation for it is queued, so the $server
  //    stub announces one when nothing is open yet.
  {
    const warnings = [];
    const consoleWarn = console.warn;
    console.warn = (text) => warnings.push(text);
    let batches = [];
    let interval = null;
    const cs = {
      state: 'connected',
      cbs: [],
      addStateChangeListener(cb) { this.cbs.push(cb); },
      go(to) { const prev = this.state; this.state = to; this.cbs.forEach((cb) => cb(prev, to)); }
    };
    const clients = { app: { events: new EventTarget() } };
    const win = {
      handlers: {},
      addEventListener(name, cb) { (this.handlers[name] = this.handlers[name] || []).push(cb); },
      location: { pathname: '/orders/17', href: 'https://app.example.com/orders/17' },
      sessionStorage: { store: {}, getItem(k) { return this.store[k] === undefined ? null : this.store[k]; }, setItem(k, v) { this.store[k] = v; }, removeItem(k) { delete this.store[k]; } },
      Vaadin: { connectionState: cs, Flow: { clients: clients } }
    };
    // The request the client is collecting invocations into, if any.
    let open = null;
    function announce(target, type, detail) {
      target.dispatchEvent(new CustomEvent(type, { detail: detail }));
      return detail;
    }
    function queue(client) {
      if (open === null) {
        const request = new EventTarget();
        open = announce((client || clients.app).events, 'vaadin-request', request);
      }
      return open;
    }
    // Set to have the call fail after Flow has already queued it.
    let failAfterQueue = false;
    // Set to have the call queued without its request being announced.
    let announceLate = false;
    const doc = {
      querySelector: (sel) => (sel === 'vaadin-metrics-collector'
        ? { hasAttribute: () => false, $server: { recordSamples: (batch) => {
          const request = announceLate ? null : queue();
          if (failAfterQueue) { throw new Error('rejected'); }
          batches.push({ batch: batch, request: request });
          return Promise.resolve();
        } } }
        : null),
      addEventListener() {},
      visibilityState: 'visible'
    };
    let now = 50000;
    const perf = { getEntriesByType: () => [], now: () => now };
    new Function('window', 'document', 'performance', 'PerformanceObserver', 'history', 'setInterval', 'requestAnimationFrame', src)(
      win, doc, perf, function () { throw new Error('unsupported'); }, {}, (cb) => { interval = cb; return 0; }, () => 0
    );
    const collectorApi = win.__vaadinMicrometer;
    const settle = () => new Promise((r) => setTimeout(r, 5));

    // Sends the open request and lets `wireMs` pass before its reply.
    function send(request, wireMs) {
      if (open === request) { open = null; }
      request.dispatchEvent(new Event('sent'));
      now += wireMs;
    }
    function respond(request) {
      request.dispatchEvent(new Event('response'));
      request.dispatchEvent(new Event('end'));
    }
    // A message from the server: announced, parsed against the request it
    // replies to, handled for `applyMs`, and ended with `outcome`. Without
    // `start` when `unstarted` is set.
    function message(request, applyMs, outcome, client, unstarted) {
      const msg = new EventTarget();
      msg.request = undefined;
      announce((client || clients.app).events, 'vaadin-server-message', msg);
      msg.request = request;
      msg.dispatchEvent(new Event('parsed'));
      if (!unstarted) { msg.dispatchEvent(new Event('start')); }
      now += applyMs;
      msg.outcome = outcome;
      msg.dispatchEvent(new Event('end'));
    }
    function click(wireMs, applyMs, client) {
      const request = queue(client);
      send(request, wireMs);
      respond(request);
      message(request, applyMs, 'applied', client);
    }
    // The flush's own request comes back, with what applying it cost.
    async function ownRoundTrip(applyMs) {
      const own = batches[batches.length - 1].request;
      send(own, 40);
      respond(own);
      message(own, applyMs, 'applied');
      await settle();
    }
    async function drain() {
      cs.go('connection-lost');
      cs.go('connected');
      await settle();
      return batches.flatMap((b) => b.batch);
    }
    function nudge() {
      win.handlers.error.forEach((cb) => cb({ message: 'x', filename: '/app.js', lineno: 1, error: err('x') }));
    }
    const timing = (all) => all.filter((s) => s.name === 'vaadin.client.request.duration' || s.name === 'vaadin.client.render.duration')
      .map((s) => [s.name.split('.')[2], s.tags.route, s.valueMs]);
    // What an action reported, as the flush after it carries it.
    async function timed(action) {
      await action();
      batches = [];
      return timing(await drain());
    }

    check('a fresh collector has timed nothing yet', collectorApi.bufferSize(), 0);

    // 8a. One click: the round trip from `sent` to `response`, and applying
    //     the reply from `start` to `end`.
    check('a round trip is timed on the wire and in the browser',
      await timed(() => click(180, 12)), [['request', '/orders/17', 180], ['render', '/orders/17', 12]]);

    // 8b. The flush that just delivered those samples is itself a UIDL
    //     request. Neither it nor its reply is timed, and the next real
    //     interaction is timed as usual.
    await ownRoundTrip(3);
    check("the collector's own flush request produces no samples", collectorApi.bufferSize(), 0);
    check('the interaction after a flush is timed normally',
      await timed(() => click(90, 30)), [['request', '/orders/17', 90], ['render', '/orders/17', 30]]);

    // 8c. A request sent again after a timeout is timed from the attempt that
    //     was answered, and a message the server pushes on its own is timed in
    //     the browser.
    await ownRoundTrip(1);
    check('a resent request is timed from its last attempt; a pushed message is rendered',
      await timed(() => {
        const request = queue();
        send(request, 5000);
        send(request, 70);
        respond(request);
        message(request, 8, 'applied');
        message(undefined, 6, 'applied');
      }), [['request', '/orders/17', 70], ['render', '/orders/17', 8], ['render', '/orders/17', 6]]);

    // 8d. A message that was not applied -- discarded, or failed while being
    //     applied -- is not a render, and an event whose beginning was never
    //     seen times nothing: a reply to a request never sent, or a message
    //     that ends without starting.
    await ownRoundTrip(1);
    check('messages that were not applied, and events missing their start, are not timed',
      await timed(() => {
        message(undefined, 4, 'discarded');
        message(undefined, 9, 'failed');
        message(undefined, 3, 'applied', undefined, true);
        const unsent = queue();
        open = null;
        respond(unsent);
        click(50, 2);
      }), [['request', '/orders/17', 50], ['render', '/orders/17', 2]]);

    // 8e. A flush that joins a request the user already opened announces
    //     nothing of its own. That request carries the user's interaction and
    //     is timed as such.
    await ownRoundTrip(1);
    check('a flush joining an open user request leaves it timed as the user\'s',
      await timed(async () => {
        const request = queue();
        nudge();
        collectorApi.flush();
        await settle();
        send(request, 120);
        respond(request);
        message(request, 15, 'applied');
      }), [['request', '/orders/17', 120], ['render', '/orders/17', 15]]);

    // 8f. A Flow client started after the collector -- an embedded
    //     application -- is subscribed on the next flush interval.
    await ownRoundTrip(1);
    clients.embedded = { events: new EventTarget() };
    interval();
    interval();
    check('a client started later is timed after the next interval',
      await timed(() => click(60, 4, clients.embedded)), [['request', '/orders/17', 60], ['render', '/orders/17', 4]]);
    await ownRoundTrip(1);
    check('and a client already listened to is not subscribed twice',
      await timed(() => click(70, 5)), [['request', '/orders/17', 70], ['render', '/orders/17', 5]]);

    // 8g. A call that fails after Flow queued it keeps the batch, and the
    //     request it opened is the last one claimed: the next interaction is
    //     timed as the user's.
    await ownRoundTrip(1);
    nudge();
    failAfterQueue = true;
    collectorApi.flush();
    failAfterQueue = false;
    open = null;
    check('a failed call keeps its batch for the next flush', collectorApi.bufferSize(), 1);
    check('and does not claim the requests after it',
      await timed(() => click(80, 6)), [['request', '/orders/17', 80], ['render', '/orders/17', 6]]);

    // 8h. A click that navigates is tagged with the view it was made on, the
    //     request and the reply alike, even though the browser shows the next
    //     view by the time either is done. A message the server pushes on its
    //     own carries the view it arrived on.
    await ownRoundTrip(1);
    check('a navigating click is tagged with the route it was made on',
      await timed(() => {
        const request = queue();
        win.location.pathname = '/customers';
        send(request, 40);
        respond(request);
        message(request, 7, 'applied');
        message(undefined, 3, 'applied');
      }), [['request', '/orders/17', 40], ['render', '/orders/17', 7], ['render', '/customers', 3]]);
    win.location.pathname = '/orders/17';

    // 8i. Nothing above is anything to warn about. A flush whose call neither
    //     joins an open request nor has one announced while it queues cannot
    //     tell its own request apart, and says so once.
    await ownRoundTrip(1);
    check('a collector that recognises its own requests warns about nothing', warnings, []);
    announceLate = true;
    nudge();
    collectorApi.flush();
    await settle();
    nudge();
    collectorApi.flush();
    await settle();
    announceLate = false;
    check('a flush whose request is not announced while queued warns once',
      warnings.map((text) => text.includes('did not announce the request carrying the client metrics')), [true]);

    // 8j. A Flow client without client.events records neither meter, and the
    //     collector says why, once.
    warnings.length = 0;
    clients.legacy = {};
    interval();
    interval();
    check('a client without events warns once that the meters are not recorded',
      warnings.map((text) => text.includes('does not announce its requests and messages on client.events')), [true]);
    console.warn = consoleWarn;
  }

  // 9. Loading the module is not attaching the element. The module is in the
  //    bundle of every application with the kit, client metrics or not, so
  //    only the element the server attaches may start the collector; and a
  //    module evaluated a second time must not define the element again.
  {
    let defined = null;
    let defines = 0;
    const registry = {
      get: (tag) => (tag === 'vaadin-metrics-collector' ? defined : undefined),
      define: (tag, type) => { defines++; defined = type; }
    };
    const win = {
      handlers: {},
      addEventListener(name, cb) { (this.handlers[name] = this.handlers[name] || []).push(cb); },
      location: { pathname: '/x', href: 'https://app.example.com/x' },
      sessionStorage: { store: {}, getItem(k) { return this.store[k] === undefined ? null : this.store[k]; }, setItem(k, v) { this.store[k] = v; }, removeItem(k) { delete this.store[k]; } }
    };
    const load = () => new Function('window', 'document', 'performance', 'PerformanceObserver', 'history', 'setInterval', 'requestAnimationFrame', 'customElements', 'HTMLElement', src)(
      win,
      { querySelector: () => null, addEventListener() {}, visibilityState: 'visible' },
      { getEntriesByType: () => [], now: () => 0 },
      function () { throw new Error('unsupported'); },
      {},
      () => 0,
      () => 0,
      registry,
      class {}
    );

    load();
    check('loading the module defines the element', defines, 1);
    check('but installs nothing', win.__vaadinMicrometerInstalled, undefined);
    check('and listens to nothing', Object.keys(win.handlers), []);

    load();
    check('a second load does not define the element again', defines, 1);

    new defined().connectedCallback();
    check('attaching the element installs the collector', win.__vaadinMicrometerInstalled, true);
    check('which then listens for errors', (win.handlers.error || []).length > 0, true);
  }

  // 10. LCP is one sample per page load: whichever candidate is current when
  //     the user first interacts or the page is hidden. The panel sees the
  //     candidates as they come, without any of them going to the server.
  {
    const lcpWith = (finish) => {
      let observer = null;
      let pending = [];
      const handlers = {};
      const on = (name, cb) => { (handlers[name] = handlers[name] || []).push(cb); };
      const win = {
        addEventListener: on,
        location: { pathname: '/orders', href: 'https://app.example.com/orders' },
        sessionStorage: { store: {}, getItem(k) { return this.store[k] === undefined ? null : this.store[k]; }, setItem(k, v) { this.store[k] = v; }, removeItem(k) { delete this.store[k]; } }
      };
      const doc = { querySelector: () => null, addEventListener: on, visibilityState: 'visible' };
      function Observer(cb) { this.cb = cb; }
      Observer.prototype.observe = function (opts) { if (opts.type === 'largest-contentful-paint') { observer = this; } };
      Observer.prototype.takeRecords = function () { const taken = pending; pending = []; return taken; };
      Observer.prototype.disconnect = function () { observer = null; };
      new Function('window', 'document', 'performance', 'PerformanceObserver', 'history', 'setInterval', 'requestAnimationFrame', src)(
        win, doc, { getEntriesByType: () => [], now: () => 0 }, Observer, {}, () => 0, () => 0
      );
      const api = win.__vaadinMicrometer;
      observer.cb({ getEntries: () => [{ renderTime: 300, startTime: 300 }] });
      observer.cb({ getEntries: () => [{ renderTime: 0, loadTime: 0, startTime: 900 }] });
      const seen = [api.bufferSize(), api.latest('vaadin.client.web_vitals.lcp').valueMs];
      // A candidate the browser has queued but not yet delivered still counts.
      pending = [{ renderTime: 1200, startTime: 1200 }];
      finish(handlers, doc);
      const afterFirst = api.bufferSize();
      (handlers.keydown || []).forEach((cb) => cb({}));
      (handlers.pagehide || []).forEach((cb) => cb({}));
      return { seen, afterFirst, afterMore: api.bufferSize(), latest: api.latest('vaadin.client.web_vitals.lcp').valueMs, gone: observer === null };
    };

    const byInput = lcpWith((handlers) => handlers.pointerdown.forEach((cb) => cb({})));
    check('LCP candidates are not sent as they come', byInput.seen[0], 0);
    check('but the panel sees the current one', byInput.seen[1], 900);
    check('the first input reports LCP once, with the queued candidate', [byInput.afterFirst, byInput.latest], [1, 1200]);
    check('later inputs and hiding report nothing more', byInput.afterMore, 1);
    check('and the observer is let go', byInput.gone, true);

    const byHiding = lcpWith((handlers, doc) => {
      doc.visibilityState = 'hidden';
      handlers.visibilitychange.forEach((cb) => cb({}));
    });
    check('hiding the page reports LCP once', [byHiding.afterFirst, byHiding.afterMore], [1, 1]);
  }

  process.exit(failures === 0 ? 0 : 1);
})();
