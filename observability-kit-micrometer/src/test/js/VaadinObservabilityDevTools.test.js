// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Runs the real dev-tools panel against a stubbed Copilot and feeds it the
// messages ObservabilityDevToolsHandler sends. Covers what the panel decides
// on its own, none of which the server-side tests can see: the order findings
// are shown in, that page-authored text reaches innerHTML escaped, which
// meters belong to the route the browser is on, when a finding is announced,
// and what is polled while the panel is closed.
//
//   node observability-kit-micrometer/src/test/js/VaadinObservabilityDevTools.test.js
//
// No dependencies and no runner, like the collector suite next to it: the
// module has no JavaScript build. Exits non-zero on failure, and `mvn test`
// runs it that way. Skipped with -Dskip.js.tests when node is not on PATH.
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(
  path.join(__dirname, '../../main/resources/META-INF/frontend/VaadinObservabilityDevTools.js'),
  'utf8'
);

// Enough of an element for the panel: it sets innerHTML on itself once, then
// only on the two region children it looked up. Nothing here parses HTML --
// the assertions read the strings the panel produced, which is the artefact
// that would carry an unescaped script into the developer's dev-tools window.
class FakeElement {
  constructor() {
    this.style = {};
    this.innerHTML = '';
    this.regions = {};
    this.listeners = {};
  }
  addEventListener(name, cb) {
    (this.listeners[name] = this.listeners[name] || []).push(cb);
  }
  querySelector(selector) {
    return (this.regions[selector] = this.regions[selector] || { innerHTML: '' });
  }
  contains() {
    return true;
  }
}

let defined = null;
const customElements = {
  get: () => undefined,
  define: (tag, type) => {
    defined = type;
  }
};

// Copilot as the script meets it: an event bus that server messages arrive on,
// a plugins array to register through, and send() as the only way out.
const sent = [];
const logged = [];
const busListeners = {};
const copilotStub = {
  _uiState: {},
  plugins: [],
  eventbus: {
    on: (name, cb) => {
      (busListeners[name] = busListeners[name] || []).push(cb);
    },
    emit: (name, detail) => {
      logged.push({ name, detail });
    }
  },
  send: (command) => {
    sent.push(command);
  },
  addPanel: (configuration) => {
    panels.push(configuration);
  }
};
const panels = [];

// The browser is on /orders/17, which is what makes 'orders/:orderId' the
// current route below.
const win = {
  location: { pathname: '/orders/17' },
  Vaadin: { copilot: copilotStub }
};

// Intervals are captured, never run on their own: every tick in this suite is
// one the test asked for.
const intervals = [];
const navigatorStub = {};

new Function(
  'window',
  'customElements',
  'HTMLElement',
  'setInterval',
  'clearInterval',
  'navigator',
  src
)(
  win,
  customElements,
  FakeElement,
  (fn, ms) => intervals.push({ fn, ms }) && 0,
  () => {},
  navigatorStub
);

let failures = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `\n        got ${JSON.stringify(actual)} want ${JSON.stringify(expected)}`}`);
}

// The bootstrap poll: it waits for Copilot, then registers the plugin. Copilot
// itself overrides push() to init immediately; here the test does the init.
intervals[0].fn();
check('the plugin registers once Copilot is up', copilotStub.plugins.length, 1);
const plugin = copilotStub.plugins[0];
plugin.init(copilotStub);

const pollTick = intervals[intervals.length - 1].fn;

// A server message as Copilot delivers it: on the event bus, ahead of any
// panel, with preventDefault() the way a listener claims it.
let unclaimed = 0;
function deliver(command, data) {
  const listeners = busListeners[command] || [];
  if (listeners.length === 0) {
    unclaimed++;
    return;
  }
  let claimed = false;
  listeners.forEach((cb) => cb({ detail: data, preventDefault: () => (claimed = true) }));
  if (!claimed) unclaimed++;
}

const insight = (type, severity, summary, evidence, extra) =>
  Object.assign(
    {
      type: type,
      severity: severity,
      category: 'reliability',
      summary: summary,
      evidence: evidence,
      replay: ['Open route ' + evidence.route],
      suggestion: 'Inspect ' + (evidence.frame || evidence.component)
    },
    extra || {}
  );

const SLOW = insight('slow-user-interaction', 'warning', 'slow save', {
  route: 'orders/:orderId',
  component: 'com.example.SaveButton',
  event: 'click',
  occurrences: 9,
  lastSeen: '2026-09-08T10:00:09Z'
});
const FAILING_SAVE = insight('user-interaction-error', 'error', 'failing save', {
  route: 'orders/:orderId',
  component: 'com.example.SaveButton',
  event: 'click',
  exception: 'java.lang.NullPointerException',
  occurrences: 2,
  lastSeen: '2026-09-08T10:00:02Z'
});
const CHART_ERROR = insight('client-error', 'error', 'browser error in chart', {
  route: 'dashboard',
  kind: 'uncaught',
  source: '/VAADIN/chart.js',
  frame: '/VAADIN/chart.js:12:9',
  occurrences: 7,
  lastSeen: '2026-09-08T10:00:07Z'
});
const payload = (insights, instrumentation) => ({
  schemaVersion: 1,
  generated: '2026-09-08T10:00:00Z',
  instrumentation: instrumentation || 'active',
  insights: insights
});

// 1. The watch runs with no panel open: this is the whole point of holding the
// state at module scope. Nothing from the first payload is announced -- the
// buffers outlive a reload, so everything in it predates this page.
deliver('observability-kit-insights-data', payload([SLOW]));
check('the first payload announces nothing', logged, []);

// A finding the payload did not have before is announced once, by severity,
// and never again however many polls repeat it.
deliver('observability-kit-insights-data', payload([SLOW, FAILING_SAVE]));
check('a new finding is announced in the Copilot log', logged.map((e) => e.name), ['log']);
check('as an error, so the log flags it', logged[0].detail.type, 'error');
check('with the summary the server wrote', logged[0].detail.message, 'Observability: failing save');
deliver('observability-kit-insights-data', payload([SLOW, FAILING_SAVE]));
check('the same finding does not announce twice', logged.length, 1);
deliver('observability-kit-insights-data', payload([SLOW, FAILING_SAVE, CHART_ERROR]));
check('a second new finding announces once', logged.length, 2);
check('and it is the new one', logged[1].detail.message, 'Observability: browser error in chart');

// 2. Polling: with the panel closed the meters are not asked for at all, and
// the insights only every fifth tick.
sent.length = 0;
for (let i = 0; i < 5; i++) pollTick();
check('a closed panel polls insights only, once per five ticks', sent, ['observability-kit-insights']);

const panel = new defined();
panel.connectedCallback();
sent.length = 0;
pollTick();
check('an open panel asks for both', sent, ['observability-kit-refresh', 'observability-kit-insights']);

const insightsHtml = () => panel.regions['[data-region="insights"]'].innerHTML;
const metricsHtml = () => panel.regions['[data-region="metrics"]'].innerHTML;

// 3. Ranking: warnings below errors however recent, and the most-reported
// error first. The server sends them in the endpoint's order, worst last here.
deliver('observability-kit-insights-data', payload([SLOW, FAILING_SAVE, CHART_ERROR]));
const order = ['browser error in chart', 'failing save', 'slow save'].map((summary) =>
  insightsHtml().indexOf(summary)
);
check('errors rank above warnings, most-reported error first', order.every((at, i) => at >= 0 && (i === 0 || at > order[i - 1])), true);
check('the header counts the findings', insightsHtml().includes('3 findings need attention'), true);

// 4. Meters, grouped by the route they were recorded on. The browser is on
// /orders/17, so the group for 'orders/:orderId' comes first and says so;
// then the other routes alphabetically, the unresolved sentinel after them,
// and the meters carrying no route at all in a general section.
const meter = (name, tags, count) => ({ name, type: 'COUNTER', tags: tags, count: count });
deliver('observability-kit-metrics', {
  timestamp: Date.parse('2026-09-08T10:00:11Z'),
  meters: [
    meter('vaadin.errors', { route: 'dashboard', exception: 'IllegalState' }, 1),
    meter('vaadin.sessions', {}, 4),
    meter('vaadin.rpc.duration', { route: 'orders/:orderId', outcome: 'success' }, 12),
    meter('vaadin.navigation', { route: '_unknown' }, 2)
  ]
});
check('the meters are collapsed while findings are showing', metricsHtml().includes('vaadin.rpc.duration'), false);
click('toggle-metrics');
const groupOrder = ['orders/:orderId', 'dashboard', 'Route not resolved', 'General'].map((name) =>
  metricsHtml().indexOf(name)
);
check('groups run current route, other routes, sentinel, general', groupOrder.every((at, i) => at >= 0 && (i === 0 || at > groupOrder[i - 1])), true);
check('the current route says which one it is', metricsHtml().includes('current page'), true);
check('a route group does not repeat the route on every row', metricsHtml().includes('route=orders'), false);
check('the other tags of a grouped meter are still shown', metricsHtml().includes('outcome=success'), true);
check('a meter with no route is in the general group', metricsHtml().indexOf('vaadin.sessions') > metricsHtml().indexOf('General'), true);

// 5. Escaping. `frame`, `source`, a client-error `message` and the summary
// built from them are page-authored text, and this panel runs inside the
// developer's dev-tools window.
const HOSTILE = insight('client-error', 'error', 'A browser error at <script>alert(1)</script>', {
  route: 'dashboard',
  kind: 'uncaught',
  source: '<img src=x onerror=alert(2)>',
  frame: '<script>alert(3)</script>',
  message: '<b>boom</b>',
  occurrences: 1,
  lastSeen: '2026-09-08T10:00:01Z'
});
deliver('observability-kit-insights-data', payload([HOSTILE]));
check('the summary is escaped', insightsHtml().includes('<script>'), false);
check('the meta line is escaped', insightsHtml().includes('<img src=x'), false);
check('the escaped text is still shown', insightsHtml().includes('&lt;script&gt;alert(1)&lt;/script&gt;'), true);

// 6. Expanding a row shows the evidence, the replay steps and the suggestion --
// escaped as well, since the evidence is where the reported message lands.
check('the detail is folded away until asked for', insightsHtml().includes('Suggestion'), false);
click('toggle-insight', 0);
check('expanding shows the replay steps', insightsHtml().includes('Replay'), true);
check('expanding shows the suggestion', insightsHtml().includes('Suggestion'), true);
check('the evidence message is escaped', insightsHtml().includes('<b>boom</b>'), false);
check('the evidence message is shown', insightsHtml().includes('&lt;b&gt;boom&lt;/b&gt;'), true);

// 7. A poll that changes nothing must not rebuild the section: it would close
// the row opened above and drop a selection mid-copy. The timestamp moves on
// every poll, which is why it lives on the metrics header and not here.
const before = insightsHtml();
panel.regions['[data-region="insights"]'].innerHTML = 'untouched';
deliver('observability-kit-insights-data', payload([HOSTILE]));
check('an unchanged poll leaves the insights alone', insightsHtml(), 'untouched');

const HOSTILE_AGAIN = insight('client-error', 'error', 'A browser error at <script>alert(1)</script>', {
  route: 'dashboard',
  kind: 'uncaught',
  source: '<img src=x onerror=alert(2)>',
  frame: '<script>alert(3)</script>',
  message: '<b>boom</b>',
  occurrences: 2,
  lastSeen: '2026-09-08T10:00:11Z'
});
deliver('observability-kit-insights-data', payload([HOSTILE_AGAIN]));
check('a changed poll rebuilds them', insightsHtml() !== 'untouched', true);
check('the row that was open stays open', insightsHtml().includes('Suggestion'), true);
check('the new occurrence count is shown', insightsHtml().includes('2 occurrences'), true);
check('the rebuild is not the old html', insightsHtml() === before, false);
check('a recurrence does not announce again', logged.length, 3);

// 8. Copy hands over the whole finding, not the summary line: the payload is
// built to travel into an issue or an agent with codebase access.
let copied = null;
navigatorStub.clipboard = {
  writeText: (text) => {
    copied = text;
    return Promise.resolve();
  }
};
click('copy-insight', 0);
check('copy puts the whole insight on the clipboard', JSON.parse(copied).type, 'client-error');
check('copy carries the evidence with it', JSON.parse(copied).evidence.occurrences, 2);
check('copying does not toggle the row it sits in', insightsHtml().includes('Suggestion'), true);

// 9. Nothing retained is not the same answer as nothing collected.
deliver('observability-kit-insights-data', payload([], 'inactive'));
check('inactive instrumentation names the setting', insightsHtml().includes('vaadin.observability.insights'), true);
deliver('observability-kit-insights-data', payload([]));
check('active instrumentation with nothing to report says so', insightsHtml().includes('No problems detected yet'), true);

// 10. Every message this suite delivered was claimed. An unclaimed one is
// queued by Copilot for a panel that may never open, and these arrive every
// few seconds.
check('every server message was claimed on the event bus', unclaimed, 0);

// A click as the delegated listener sees one: the panel resolves the innermost
// element carrying data-action, so a stub of that element is the whole event.
function click(action, index) {
  panel.handleClick({
    target: {
      closest: () => ({
        getAttribute: (name) => (name === 'data-action' ? action : String(index)),
        setAttribute() {},
        set textContent(value) {}
      })
    }
  });
}

process.exit(failures === 0 ? 0 : 1);
