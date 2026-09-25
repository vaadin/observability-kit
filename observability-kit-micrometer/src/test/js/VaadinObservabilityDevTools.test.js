// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Runs the real dev-tools panel against a stubbed Copilot and feeds it the
// messages ObservabilityDevToolsHandler sends. Covers what the panel decides
// on its own, none of which the server-side tests can see: the order findings
// are shown in, that page-authored text reaches innerHTML escaped, which
// meters belong to the route the browser is on, which findings are worth
// announcing, and what is polled while the panel is closed.
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

let failures = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `\n        got ${JSON.stringify(actual)} want ${JSON.stringify(expected)}`}`);
}

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

// Clicks are resolved out of the markup the panel actually rendered, not out
// of what a test would like to have happened: find the text, walk out to the
// innermost element carrying data-action, and hand the panel exactly the
// attributes that element has. A Copy button wired to the wrong action fails
// here instead of passing on a stub's say-so.
const VOID_TAGS = ['polyline', 'br', 'img', 'input'];

function closestActionAt(html, offset) {
  const tags = /<(\/?)([a-zA-Z][\w-]*)((?:"[^"]*"|[^>"])*?)(\/?)>/g;
  const stack = [];
  let tag;
  while ((tag = tags.exec(html)) !== null) {
    if (tag.index > offset) {
      break;
    }
    const [, closing, name, attributes, selfClosing] = tag;
    if (closing) {
      stack.pop();
      continue;
    }
    if (selfClosing || VOID_TAGS.indexOf(name) >= 0) {
      continue;
    }
    const action = /data-action="([^"]*)"/.exec(attributes);
    const index = /data-index="([^"]*)"/.exec(attributes);
    stack.push(action ? { action: action[1], index: index ? index[1] : null } : null);
  }
  for (let i = stack.length - 1; i >= 0; i--) {
    if (stack[i]) {
      return stack[i];
    }
  }
  return null;
}

/**
 * The script in an environment of its own: its own Copilot, its own browser
 * location, its own module state. A second one is the only way to ask what a
 * panel does before anything has been clicked in it.
 */
function harness(pathname) {
  const sent = [];
  const announced = [];
  const busListeners = {};
  const intervals = [];
  const navigatorStub = {};
  let defined = null;

  // Copilot's event bus, with the part the announcement rests on modelled
  // rather than stubbed out: an event whose type nothing is listening for is
  // buffered, and the first listener to subscribe is handed the buffer before
  // it sees anything live. That is what carries an announcement across a log
  // panel that is closed when the finding appears, so a fake that recorded
  // every emit would pass whether or not the buffer existed.
  //
  // Copied from the shipped bundle (copilot.js, the class behind
  // window.Vaadin.copilot.eventbus): emit() pushes onto eventBuffer unless the
  // type is in handledTypes, on() appends to handledTypes and then flushes
  // that type, and off() removes one entry from handledTypes.
  const handledTypes = [];
  const eventBuffer = [];

  function dispatch(name, data) {
    let claimed = false;
    (busListeners[name] || []).forEach((cb) =>
      cb({ detail: data, preventDefault: () => (claimed = true) })
    );
    return claimed;
  }

  const eventbus = {
    on: (name, cb) => {
      (busListeners[name] = busListeners[name] || []).push(cb);
      handledTypes.push(name);
      for (let i = 0; i < eventBuffer.length; i++) {
        if (eventBuffer[i].name === name) {
          dispatch(name, eventBuffer[i].data);
          eventBuffer.splice(i, 1);
          i--;
        }
      }
      return () => eventbus.off(name, cb);
    },
    off: (name, cb) => {
      const listeners = busListeners[name] || [];
      const at = listeners.indexOf(cb);
      if (at >= 0) listeners.splice(at, 1);
      const typeAt = handledTypes.indexOf(name);
      if (typeAt >= 0) handledTypes.splice(typeAt, 1);
    },
    // What the module asked the bus to write, whether or not anything was
    // listening. The log panel's own view of it is `logged` below.
    emit: (name, data) => {
      if (name === 'log') {
        announced.push(data);
      }
      if (!handledTypes.includes(name)) {
        eventBuffer.push({ name: name, data: data });
      }
      return dispatch(name, data);
    }
  };

  // Copilot's log panel, as far as an announcement can tell: it subscribes to
  // 'log' when it opens and unsubscribes when it closes.
  const logged = [];
  const logListener = (event) => logged.push(event.detail);

  let panelsAdded = 0;
  const copilot = {
    _uiState: {},
    plugins: [],
    eventbus: eventbus,
    send: (command, data) => {
      sent.push({ command, data });
    },
    addPanel: () => {
      panelsAdded++;
    }
  };
  const win = { location: { pathname: pathname }, Vaadin: { copilot } };

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
    { get: () => undefined, define: (tag, type) => (defined = type) },
    FakeElement,
    (fn, ms) => intervals.push({ fn, ms }) && 0,
    () => {},
    navigatorStub
  );

  // The bootstrap poll waits for Copilot, then registers the plugin. Copilot
  // itself overrides push() to init immediately; here the harness does it.
  intervals[0].fn();
  const plugin = copilot.plugins[0];
  plugin.init(copilot);

  const panel = new defined();
  let unclaimed = 0;

  // A server message as Copilot delivers it: on the event bus, ahead of any
  // panel, with preventDefault() the way a listener claims it.
  function deliver(command, data) {
    if (!dispatch(command, data)) unclaimed++;
  }

  const insightsHtml = () => panel.regions['[data-region="insights"]'].innerHTML;
  const metricsHtml = () => panel.regions['[data-region="metrics"]'].innerHTML;

  function clickIn(html, needle) {
    const found = html.indexOf(needle);
    if (found < 0) {
      failures++;
      console.log(`FAIL  nothing rendered matching ${JSON.stringify(needle)}`);
      return {};
    }
    const target = closestActionAt(html, found);
    if (!target) {
      failures++;
      console.log(`FAIL  ${JSON.stringify(needle)} is in no element carrying data-action`);
      return {};
    }
    panel.handleClick({
      target: {
        closest: () => ({
          getAttribute: (name) => (name === 'data-action' ? target.action : target.index),
          setAttribute() {},
          set textContent(value) {}
        })
      }
    });
    return target;
  }

  return {
    panel,
    win,
    sent,
    announced,
    navigator: navigatorStub,
    open: () => panel.connectedCallback(),
    close: () => panel.disconnectedCallback(),
    pollTick: () => intervals[intervals.length - 1].fn(),
    logged,
    openLogPanel: () => eventbus.on('log', logListener),
    closeLogPanel: () => eventbus.off('log', logListener),
    insights: (data) => deliver('observability-kit-insights-data', data),
    meters: (data) => deliver('observability-kit-metrics', data),
    insightsHtml,
    metricsHtml,
    clickIn,
    commands: () => sent.map((message) => message.command),
    panelsAdded: () => panelsAdded,
    unclaimed: () => unclaimed
  };
}

// Findings are placed against the moment the script loaded, which is what it
// stamped as the page start: older than that predates the page, younger is
// something this page is responsible for.
const loaded = Date.now();
const at = (offset) => new Date(loaded + offset).toISOString();
const BEFORE_THE_PAGE = at(-60000);
const SINCE_THE_PAGE = at(5000);

const insight = (type, severity, summary, evidence) => ({
  type: type,
  severity: severity,
  category: 'reliability',
  summary: summary,
  evidence: evidence,
  replay: ['Open route ' + evidence.route],
  suggestion: 'Inspect ' + (evidence.frame || evidence.component)
});

const SLOW = insight('slow-user-interaction', 'warning', 'slow save', {
  route: 'orders/:orderId',
  component: 'com.example.SaveButton',
  event: 'click',
  occurrences: 9,
  firstSeen: BEFORE_THE_PAGE,
  lastSeen: at(9000)
});
const LOAD_TIME = insight('slow-data-query', 'warning', 'slow grid query on load', {
  route: 'orders/:orderId',
  component: 'com.example.OrderGrid',
  queryKind: 'fetch',
  occurrences: 1,
  firstSeen: SINCE_THE_PAGE,
  lastSeen: SINCE_THE_PAGE
});
const FAILING_SAVE = insight('user-interaction-error', 'error', 'failing save', {
  route: 'orders/:orderId',
  component: 'com.example.SaveButton',
  event: 'click',
  exception: 'java.lang.NullPointerException',
  occurrences: 2,
  firstSeen: SINCE_THE_PAGE,
  lastSeen: at(6000)
});
// Same route, component and event as the one above, a different exception --
// which is what the server groups on too.
const FAILING_SAVE_OTHER = insight('user-interaction-error', 'error', 'failing save, other cause', {
  route: 'orders/:orderId',
  component: 'com.example.SaveButton',
  event: 'click',
  exception: 'java.lang.IllegalStateException',
  occurrences: 1,
  firstSeen: SINCE_THE_PAGE,
  lastSeen: at(7000)
});
const CHART_ERROR = insight('client-error', 'error', 'browser error in chart', {
  route: 'dashboard',
  kind: 'uncaught',
  source: '/VAADIN/chart.js',
  frame: '/VAADIN/chart.js:12:9',
  occurrences: 7,
  firstSeen: BEFORE_THE_PAGE,
  lastSeen: at(7000)
});
const HOSTILE = insight('client-error', 'error', 'A browser error at <script>alert(1)</script>', {
  route: 'dashboard',
  kind: 'uncaught',
  source: '<img src=x onerror=alert(2)>',
  frame: '<script>alert(3)</script>',
  message: '<b>boom</b>',
  occurrences: 1,
  firstSeen: BEFORE_THE_PAGE,
  lastSeen: at(1000)
});

// A summary longer than a log line should be. The server writes short ones,
// but nothing in the panel guarantees that, and the cut is the panel's to make.
const LONG_WINDED = insight('client-error', 'error', 'A browser error: ' + 'x'.repeat(500), {
  route: 'reports',
  kind: 'uncaught',
  source: '/VAADIN/report.js',
  frame: '/VAADIN/report.js:3:1',
  occurrences: 1,
  firstSeen: SINCE_THE_PAGE,
  lastSeen: at(8000)
});

let generation = 0;
const payload = (insights, instrumentation) => ({
  schemaVersion: 1,
  // Each payload is newer than the last, the way the server's are.
  generated: new Date(loaded + ++generation).toISOString(),
  instrumentation: instrumentation || 'active',
  insights: insights
});
const meter = (name, tags) => ({ name, type: 'COUNTER', tags: tags, count: 1 });

const app = harness('/orders/17');
app.open();

// 1. Before anything has arrived the panel has no answer, and "no problems
// detected" would be one.
check('an unanswered panel does not claim there is nothing wrong', app.insightsHtml().includes('No problems detected'), false);
check('it says it is waiting instead', app.insightsHtml().includes('Waiting for the first'), true);

// 2. Announcements. A finding raised before this page started is not news --
// the server's buffers outlive a reload -- but one raised since is, including
// on the very first payload, which is where a slow query on the landing view
// shows up.
app.insights(payload([SLOW, LOAD_TIME]));
check('the first payload announces what this page raised', app.announced.map((a) => a.message), ['Observability: slow grid query on load']);

app.insights(payload([SLOW, LOAD_TIME, FAILING_SAVE]));
check('a new finding is announced', app.announced.length, 2);
check('as an error, so the Copilot log flags it', app.announced[1].type, 'error');
check('with the summary the server wrote', app.announced[1].message, 'Observability: failing save');
app.insights(payload([SLOW, LOAD_TIME, FAILING_SAVE]));
check('the same finding does not announce twice', app.announced.length, 2);

// The same handler failing for a different reason is a different finding: the
// server groups by exception type, so the key here has to as well.
app.insights(payload([SLOW, LOAD_TIME, FAILING_SAVE, FAILING_SAVE_OTHER]));
check('a second exception on the same handler is its own finding', app.announced.length, 3);
check('and it is the new one', app.announced[2].message, 'Observability: failing save, other cause');
// Written on the event bus, not asked of the server: Copilot offers a server
// message to the event bus and then to every open panel, and the log panel
// takes 'log' from both - so a relayed announcement is logged twice.
check('an announcement is written straight to the log', app.commands().includes('observability-kit-announce'), false);

// 3. The meters folded themselves away when the first payload turned out to
// have findings in it, which is the panel opening on what is wrong.
check('findings fold the meter section', app.metricsHtml().includes('▸'), true);

// 4. Copilot replays a message no panel claimed, so the snapshot pushed at
// connect time arrives after the first polls have been answered. Taking it
// would undo them.
const shown = app.insightsHtml();
app.insights({
  schemaVersion: 1,
  generated: new Date(loaded - 10000).toISOString(),
  instrumentation: 'active',
  insights: []
});
check('a payload older than the one in hand is ignored', app.insightsHtml(), shown);
app.meters({ timestamp: loaded + 100, meters: [meter('vaadin.sessions', {})] });
app.meters({ timestamp: loaded - 100, meters: [] });
// The header counts the meters whether or not the table is unfolded, so this
// reads the state without disturbing it.
check('a meter snapshot older than the one in hand is ignored', app.metricsHtml().includes('1 meter'), true);

// 5. Polling: with the panel closed the meters are not asked for at all, and
// the insights only every fifth tick.
app.close();
app.sent.length = 0;
for (let i = 0; i < 5; i++) app.pollTick();
check('a closed panel polls insights only, once per five ticks', app.commands(), ['observability-kit-insights']);

app.open();
app.sent.length = 0;
app.pollTick();
check('an open panel asks for both', app.commands(), ['observability-kit-refresh', 'observability-kit-insights']);

// 6. Ranking: warnings below errors however recent, and the most-reported
// error first. The server sends them in the endpoint's order, worst last here.
app.insights(payload([SLOW, FAILING_SAVE, CHART_ERROR]));
const order = ['browser error in chart', 'failing save', 'slow save'].map((summary) =>
  app.insightsHtml().indexOf(summary)
);
check('errors rank above warnings, most-reported error first', order.every((found, i) => found >= 0 && (i === 0 || found > order[i - 1])), true);
check('the header counts the findings', app.insightsHtml().includes('3 findings need attention'), true);

// 7. Meters, grouped by the route they were recorded on. The browser is on
// /orders/17, so the group for 'orders/:orderId' comes first and says so;
// then the other routes alphabetically, the unresolved sentinel after them,
// and the meters carrying no route at all in a general section.
app.meters({
  timestamp: loaded + 11000,
  meters: [
    meter('vaadin.errors', { route: 'dashboard', exception: 'IllegalState' }),
    meter('vaadin.sessions', {}),
    meter('vaadin.rpc.duration', { route: 'orders/:orderId', outcome: 'success' }),
    meter('vaadin.navigation', { route: '_unknown' }),
    meter('vaadin.request.duration', { route: '' })
  ]
});
check('the meters are collapsed while findings are showing', app.metricsHtml().includes('vaadin.rpc.duration'), false);
check('the metrics header is what unfolds them', app.clickIn(app.metricsHtml(), '<span>Metrics</span>').action, 'toggle-metrics');
const groupOrder = ['orders/:orderId', 'Root', 'dashboard', 'Route not resolved', 'General'].map((name) =>
  app.metricsHtml().indexOf(name)
);
check('groups run current route, other routes, sentinel, general', groupOrder.every((found, i) => found >= 0 && (i === 0 || found > groupOrder[i - 1])), true);
check('the current route says which one it is', app.metricsHtml().includes('current page'), true);
check('the root view is named rather than left blank', app.metricsHtml().includes('>Root<'), true);
check('a route group does not repeat the route on every row', app.metricsHtml().includes('route=orders'), false);
check('the other tags of a grouped meter are still shown', app.metricsHtml().includes('outcome=success'), true);
check('a meter with no route is in the general group', app.metricsHtml().indexOf('vaadin.sessions') > app.metricsHtml().indexOf('General'), true);

// 8. Escaping. `frame`, `source`, a client-error `message` and the summary
// built from them are page-authored text, and this panel runs inside the
// developer's dev-tools window.
app.insights(payload([HOSTILE]));
check('the summary is escaped', app.insightsHtml().includes('<script>'), false);
check('the meta line is escaped', app.insightsHtml().includes('<img src=x'), false);
check('the escaped text is still shown', app.insightsHtml().includes('&lt;script&gt;alert(1)&lt;/script&gt;'), true);

// 9. Expanding a row shows the evidence, the replay steps and the suggestion --
// escaped as well, since the evidence is where the reported message lands.
check('the detail is folded away until asked for', app.insightsHtml().includes('Suggestion'), false);
check('the summary row is what expands it', app.clickIn(app.insightsHtml(), 'A browser error at').action, 'toggle-insight');
check('expanding shows the replay steps', app.insightsHtml().includes('Replay'), true);
check('expanding shows the suggestion', app.insightsHtml().includes('Suggestion'), true);
check('the evidence message is escaped', app.insightsHtml().includes('<b>boom</b>'), false);
check('the evidence message is shown', app.insightsHtml().includes('&lt;b&gt;boom&lt;/b&gt;'), true);

// 10. A poll that changes nothing must not rebuild the section: it would close
// the row opened above and drop a selection mid-copy. The timestamp moves on
// every poll, which is why it lives on the metrics header and not here.
const before = app.insightsHtml();
const announcedSoFar = app.announced.length;
app.panel.regions['[data-region="insights"]'].innerHTML = 'untouched';
app.insights(payload([HOSTILE]));
check('an unchanged poll leaves the insights alone', app.insightsHtml(), 'untouched');

const HOSTILE_AGAIN = JSON.parse(JSON.stringify(HOSTILE));
HOSTILE_AGAIN.evidence.occurrences = 2;
HOSTILE_AGAIN.evidence.lastSeen = at(11000);
app.insights(payload([HOSTILE_AGAIN]));
check('a changed poll rebuilds them', app.insightsHtml() !== 'untouched', true);
check('the row that was open stays open', app.insightsHtml().includes('Suggestion'), true);
check('the new occurrence count is shown', app.insightsHtml().includes('2 occurrences'), true);
check('the rebuild is not the old html', app.insightsHtml() === before, false);
check('a recurrence does not announce again', app.announced.length, announcedSoFar);

// 11. Copy hands over the whole finding, not the summary line: the payload is
// built to travel into an issue or an agent with codebase access.
let copied = null;
app.navigator.clipboard = {
  writeText: (text) => {
    copied = text;
    return Promise.resolve();
  }
};
// The button sits inside the row, which is itself a toggle, so this is also
// the check that the innermost data-action a click resolves to is the button.
check('the Copy button carries the copy action', app.clickIn(app.insightsHtml(), '>Copy<').action, 'copy-insight');
check('copy puts the whole insight on the clipboard', JSON.parse(copied).type, 'client-error');
check('copy carries the evidence with it', JSON.parse(copied).evidence.occurrences, 2);
check('copying does not toggle the row it sits in', app.insightsHtml().includes('Suggestion'), true);

// 12. Nothing retained is not the same answer as nothing collected -- and the
// hint has to hold for either way of getting there, since `insights` being on
// is not enough on its own.
app.insights(payload([], 'inactive'));
check('inactive instrumentation names the insights setting', app.insightsHtml().includes('vaadin.observability.insights'), true);
check('and the ones it needs alongside it', app.insightsHtml().includes('errors or requests'), true);
app.insights(payload([]));
check('active instrumentation with nothing to report says so', app.insightsHtml().includes('No problems detected yet'), true);

// 13. Every message this suite delivered was claimed. An unclaimed one is
// queued by Copilot for a panel that may never open, and these arrive every
// few seconds.
check('every server message was claimed on the event bus', app.unclaimed(), 0);

// 14. A panel opened before anything has arrived renders the meter section
// unfolded, so the first click on its header has to fold it rather than agree
// with what is already on screen.
{
  const waiting = harness('/orders/17');
  waiting.open();
  check('the meters start unfolded', waiting.metricsHtml().includes('▾'), true);
  waiting.clickIn(waiting.metricsHtml(), '<span>Metrics</span>');
  check('the first click folds them', waiting.metricsHtml().includes('▸'), true);
  // And that decision is the developer's: a payload arriving afterwards does
  // not re-fold or re-open on their behalf.
  waiting.insights(payload([]));
  check('a later payload leaves the fold alone', waiting.metricsHtml().includes('▸'), true);
}

// 14b. The panel is offered to Copilot only once the server answers, which it
// does only when the kit is active; and only once however many answers follow.
{
  const silent = harness('/orders/17');
  silent.pollTick();
  check('no panel while the server has not answered', silent.panelsAdded(), 0);
  check('but the insights are still asked for', silent.commands().includes('observability-kit-insights'), true);
  silent.insights(payload([]));
  silent.meters({ timestamp: Date.now(), meters: [] });
  check('the first answer adds the panel, once', silent.panelsAdded(), 1);
}

// 15. A typed parameter carries its regex after the modifier, which is where
// Flow writes it. Reading the modifier off the last character instead makes
// this a required single segment, and no group is current.
{
  const deep = harness('/files/reports/2026/q3.pdf');
  deep.open();
  deep.meters({
    timestamp: loaded,
    meters: [meter('vaadin.navigation', { route: 'files/:path*([\\s\\S]*)' })]
  });
  check('a typed varargs route matches the page it describes', deep.metricsHtml().includes('current page'), true);
}
{
  const one = harness('/orders');
  one.open();
  one.meters({
    timestamp: loaded,
    meters: [meter('vaadin.navigation', { route: 'orders/:orderId?([0-9]+)' })]
  });
  check('a typed optional route matches the page without the parameter', one.metricsHtml().includes('current page'), true);
}

// 16. An announcement raised while the log panel is closed, which is the case
// the watch exists for: nobody is looking when the finding appears. It rests
// on Copilot's event bus buffering an event whose type nothing is listening
// for and handing it to the first listener that subscribes, so the fake bus
// implements that rule rather than recording the emit and calling it a day.
{
  const quiet = harness('/orders/17');
  quiet.open();
  quiet.insights(payload([FAILING_SAVE]));

  check('the announcement was written', quiet.announced.length, 1);
  check('and nothing reached a log panel that is not open', quiet.logged.length, 0);

  quiet.openLogPanel();
  check('opening the log panel delivers what was buffered', quiet.logged.map((entry) => entry.message), ['Observability: failing save']);

  // Handed over, not kept: a buffer that replayed itself to each new listener
  // would be a second way to log one finding twice.
  quiet.closeLogPanel();
  quiet.openLogPanel();
  check('and hands it over once', quiet.logged.length, 1);
}

// 17. The same announcement with the log panel already open arrives live, and
// once - one finding logged twice is the bug this path was rebuilt for.
{
  const watching = harness('/orders/17');
  watching.open();
  watching.openLogPanel();
  watching.insights(payload([FAILING_SAVE]));
  check('an open log panel is written to directly', watching.logged.map((entry) => entry.type), ['error']);
}

// 18. A log line is a notification, not a report. The cut was the server's
// while the announcement was relayed through it, and moved here with it.
{
  const verbose = harness('/reports');
  verbose.open();
  verbose.insights(payload([LONG_WINDED]));

  const message = verbose.announced[0].message;
  check('a long summary is cut to 300 characters and an ellipsis', message.length, 301);
  check('cut rather than replaced', message.startsWith('Observability: A browser error: xxx'), true);
  check('with the ellipsis last', message.endsWith('…'), true);
}

process.exit(failures === 0 ? 0 : 1);
