// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Dev-mode Vaadin Copilot panel for observability-kit. Injected per UI by
// ObservabilityDevToolsClient via Page.executeJs (development mode only).
// Registers a Copilot plugin with four tabs. Three read the live vaadin.*
// Micrometer meters: the vitals, a handful of figures read against common
// budgets with a preview of how they would feel over a real network; the last
// click, broken down into network, server and render time, above the slowest
// things this session; and the key metrics pinned above every meter, grouped
// by the route it was recorded on. The fourth holds the insights the server
// built from the retained interactions, queries and browser errors, ranked so
// the worst is first. Meters and insights come from
// ObservabilityDevToolsHandler, as answers to two separate commands.
//
// A developer opens this panel to find out what is wrong, and a meter is a
// number: 'vaadin.errors 3' does not say which route, which component, or
// which line to open. An insight does, and carries the replay steps and the
// suggestion with it, so the top of the panel is where the insights go.
//
// Insights are polled and watched at module scope rather than by the panel
// element, because a finding is worth knowing about before anyone thinks to
// open the panel: a new one is announced in Copilot's log, which is the only
// notification surface its plugin API reaches. A panel element exists only
// while its panel is open, and so cannot be what watches. The announcement is
// a 'log' event on Copilot's event bus, which buffers an event nothing is
// listening for until the log panel opens - and the log panel is usually not
// open either.
//
// The IIFE is idempotent so repeated injection does not re-register the plugin.
//
// NOTE: this file is injected through ClientResourceLoader, which strips its
// comments with a parser that is not a JavaScript parser: a double slash with
// code after it on the same line deletes the rest of that line. Hence no regex
// literals and no URLs in string literals here. ClientResourceIntegrityTest
// enforces it.
(function () {
  if (window.__vaadinObservabilityDevToolsInstalled) {
    return;
  }
  window.__vaadinObservabilityDevToolsInstalled = true;

  var PANEL_TAG = 'observability-kit-metrics-panel';
  var COMMAND_REFRESH = 'observability-kit-refresh';
  var COMMAND_METRICS = 'observability-kit-metrics';
  var COMMAND_INSIGHTS = 'observability-kit-insights';
  var COMMAND_INSIGHTS_DATA = 'observability-kit-insights-data';
  // Copilot's own event for a log entry, which is how its log panel is written
  // to from the browser. It must not be asked for over the server instead: a
  // server message is offered to the event bus and then to every open panel,
  // and the log panel listens on both, so a 'log' command relayed from the
  // server is written to the log twice.
  var EVENT_LOG = 'log';
  // A log line is a notification, not a report. The summaries the server writes
  // are short, but nothing here guarantees that.
  var MAX_LOG_MESSAGE = 300;
  var REFRESH_INTERVAL_MS = 3000;
  // While the panel is closed the meters are not worth asking for at all and
  // the insights are not worth asking for every three seconds. One in five
  // ticks keeps the watch running at a cost nobody has to think about.
  var BACKGROUND_EVERY = 5;

  // CopilotInterface captured at plugin init; used by the panel to talk to the
  // server over the dev-tools websocket.
  var copilot = null;

  // Copilot's event bus, looked up per call rather than held: it is created
  // during Copilot's bootstrap, and the module is loaded whenever the UI
  // happens to inject it. Null when there is no Copilot to talk to.
  function eventBus() {
    var cp = window.Vaadin && window.Vaadin.copilot;
    return (cp && cp.eventbus) || null;
  }

  // The open panel element, or null while the panel is closed. Copilot creates
  // one per opening, so this is a handle for the module to render into rather
  // than an owner of any state.
  var panel = null;
  // Last meter snapshot and last insights payload received from the server,
  // shared so a freshly opened panel can render immediately before its first
  // refresh round-trips.
  var latest = null;
  var latestInsights = null;
  // How far the server's clock is ahead of this browser's, from the last
  // meter snapshot. What lets a browser-side sample be matched to the
  // server-side interaction it belongs to.
  var clockSkew = 0;
  // Per-meter ring buffer of recent trend values, keyed by name+tags. Survives
  // panel close/reopen (module scope) so the sparkline keeps its history.
  var history = {};
  var HISTORY_MAX = 20;

  // Which insight rows the developer has opened, keyed by insightKey. At
  // module scope with the history for the same reason: closing the panel to
  // look at the code and reopening it should not collapse what was being read.
  var expanded = {};

  // The panel's four tabs: three views over the meters, each answering a
  // different question - how does the app feel, where did my last click's
  // time go, what do the numbers say - and the findings.
  var TAB_VITALS = 'vitals';
  var TAB_ANATOMY = 'anatomy';
  var TAB_METRICS = 'metrics';
  var TAB_FINDINGS = 'findings';
  var TABS = [
    { key: TAB_VITALS, label: 'Vitals' },
    { key: TAB_ANATOMY, label: 'Last click' },
    { key: TAB_METRICS, label: 'Metrics' },
    { key: TAB_FINDINGS, label: 'Findings' }
  ];
  // Null until the first insights payload decides it: the findings when there
  // is something wrong, the vitals when there is not. Once the developer has
  // picked a tab, that is theirs.
  var activeTab = null;

  // The user round-trip latency the production preview adds, in ms.
  var LATENCIES = [0, 30, 80, 200];
  var prodLatency = 80;

  // The two filters on the full meter list. Both on by default: a registry is
  // mostly timers nothing has hit yet and heartbeats nobody is asking about.
  var hideIdle = true;
  var hideNoise = true;

  // ---- findings the developer is not working on right now ----------------
  //
  // "3 findings need attention" is only worth reading while all three are
  // news. A developer fixing one feature knows about the slow query in
  // another, and a finding nothing has re-triggered in half an hour is
  // history rather than attention. Both are folded away here instead of
  // being dropped: the count stays on screen and one click brings them back,
  // because a panel that silently discards findings is worse than a noisy
  // one.

  // Findings hidden by hand, keyed by insightKey, valued by when. Kept in
  // localStorage so a reload - which in development mode happens on every
  // code change - does not ask the developer to hide them all again.
  var DISMISSED_STORAGE_KEY = 'vaadin-observability-hidden-findings';
  // Entries expire, so a key for a finding nobody will see again cannot sit
  // in storage forever, and the newest survive a cap on how many are kept.
  var DISMISSED_TTL_MS = 7 * 24 * 60 * 60 * 1000;
  var DISMISSED_MAX = 200;

  /**
   * How long a finding goes unreported before the panel stops counting it as
   * needing attention.
   *
   * Measured from `lastSeen`, so anything still happening stays up however
   * old its first occurrence is, and a finding that stops recurring fades out
   * on its own - and comes straight back if it recurs, which is the property
   * that makes this safe to do automatically.
   */
  var STALE_AFTER_MS = 30 * 60 * 1000;

  var dismissed = loadDismissed();
  // Whether the folded-away findings are on screen. Not persisted: it is a
  // question about right now, not a preference.
  var showHidden = false;

  function loadDismissed() {
    var stored = {};
    try {
      var raw = window.localStorage.getItem(DISMISSED_STORAGE_KEY);
      var parsed = raw ? JSON.parse(raw) : null;
      if (parsed && typeof parsed === 'object') {
        var cutoff = Date.now() - DISMISSED_TTL_MS;
        Object.keys(parsed).forEach(function (key) {
          var when = Number(parsed[key]);
          if (isFinite(when) && when > cutoff) {
            stored[key] = when;
          }
        });
      }
    } catch (e) {
      // No storage, or something else's data under our key. Hiding then lasts
      // as long as the page does, which is worth more than failing to load.
    }
    return stored;
  }

  function saveDismissed() {
    try {
      var keys = Object.keys(dismissed).sort(function (a, b) {
        return dismissed[b] - dismissed[a];
      });
      var kept = {};
      keys.slice(0, DISMISSED_MAX).forEach(function (key) {
        kept[key] = dismissed[key];
      });
      dismissed = kept;
      window.localStorage.setItem(
        DISMISSED_STORAGE_KEY,
        JSON.stringify(dismissed)
      );
    } catch (e) {
      // As above: the in-memory map is still correct for this page.
    }
  }

  function isDismissed(insight) {
    return Object.prototype.hasOwnProperty.call(
      dismissed,
      insightKey(insight)
    );
  }

  // Deliberately not un-hidden by a recurrence. A developer who hid the slow
  // query in the feature they are not working on will keep triggering it, and
  // a dismissal that undid itself every time would be no dismissal at all.
  function dismiss(insight) {
    dismissed[insightKey(insight)] = Date.now();
    saveDismissed();
  }

  function restore(insight) {
    delete dismissed[insightKey(insight)];
    saveDismissed();
  }

  /** Whether nothing has re-triggered this finding for a while. */
  function isStale(insight) {
    var seen = Date.parse(lastSeen(insight));
    // Unparseable means the payload did not say, and a finding that cannot be
    // placed in time is not one to fold away on a guess.
    return !isNaN(seen) && Date.now() - seen > STALE_AFTER_MS;
  }

  function isHidden(insight) {
    return isDismissed(insight) || isStale(insight);
  }

  // Everything interpolated into innerHTML goes through here. Insight text is
  // not the server's to choose the way a meter name is: a client-error
  // message, its frame and its function name are strings the page reported,
  // and the page is what we are debugging.
  function esc(value) {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value)
      .split('&')
      .join('&amp;')
      .split('<')
      .join('&lt;')
      .split('>')
      .join('&gt;')
      .split('"')
      .join('&quot;')
      .split("'")
      .join('&#39;');
  }

  function num(value, decimals) {
    if (typeof value !== 'number' || !isFinite(value)) {
      return String(value);
    }
    if (Number.isInteger(value)) {
      return String(value);
    }
    return value.toFixed(decimals == null ? 1 : decimals);
  }

  function simpleName(className) {
    if (!className) {
      return '';
    }
    var lastDot = String(className).lastIndexOf('.');
    return lastDot >= 0 ? String(className).substring(lastDot + 1) : className;
  }

  function clockTime(value) {
    if (!value) {
      return '';
    }
    var when = new Date(value);
    return isNaN(when.getTime()) ? String(value) : when.toLocaleTimeString();
  }

  // The tags of a meter as one line. `skip` leaves one key out, for the caller
  // that has already said it - never for meterKey, whose whole job is that two
  // meters differing only in that tag stay two meters.
  function formatTags(tags, skip) {
    var keys = Object.keys(tags || {}).filter(function (key) {
      return key !== skip;
    });
    if (keys.length === 0) {
      return '';
    }
    return keys
      .map(function (k) {
        return k + '=' + tags[k];
      })
      .join(', ');
  }

  // Stable identity for a meter across polls (name + its tag values).
  function meterKey(meter) {
    return meter.name + '|' + formatTags(meter.tags);
  }

  // The single scalar plotted in the sparkline for this meter.
  function trendValue(meter) {
    if (typeof meter.mean === 'number') {
      return meter.mean;
    }
    if (typeof meter.value === 'number') {
      return meter.value;
    }
    if (typeof meter.count === 'number') {
      return meter.count;
    }
    if (meter.measurements && meter.measurements.length) {
      return meter.measurements[0].value;
    }
    return null;
  }

  // Append this poll's trend value to each meter's ring buffer, and drop
  // history for meters no longer reported so the map can't grow unbounded.
  function recordHistory(meters) {
    var live = {};
    (meters || []).forEach(function (meter) {
      var key = meterKey(meter);
      live[key] = true;
      var v = trendValue(meter);
      if (typeof v !== 'number' || !isFinite(v)) {
        return;
      }
      var buf = history[key] || (history[key] = []);
      buf.push(v);
      if (buf.length > HISTORY_MAX) {
        buf.shift();
      }
    });
    Object.keys(history).forEach(function (key) {
      if (!live[key]) {
        delete history[key];
      }
    });
  }

  // Inline SVG sparkline for a series of values.
  function sparkline(values) {
    if (!values || values.length < 2) {
      return '';
    }
    var w = 84;
    var h = 18;
    var pad = 2;
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    var range = max - min || 1;
    var n = values.length;
    var pts = values
      .map(function (v, i) {
        var x = pad + (i / (n - 1)) * (w - 2 * pad);
        var y = h - pad - ((v - min) / range) * (h - 2 * pad);
        return x.toFixed(1) + ',' + y.toFixed(1);
      })
      .join(' ');
    return (
      '<svg width="' +
      w +
      '" height="' +
      h +
      '" viewBox="0 0 ' +
      w +
      ' ' +
      h +
      '" style="display:block;color:var(--lumo-primary-color,#1676f3)">' +
      '<polyline points="' +
      pts +
      '" fill="none" stroke="currentColor" stroke-width="1.25" ' +
      'stroke-linejoin="round" stroke-linecap="round"/>' +
      '</svg>'
    );
  }

  // Renders a meter's value cell from the type-aware fields sent by the server.
  function formatMeterValue(meter) {
    var unit = meter.unit ? ' ' + meter.unit : '';
    // Timer / DistributionSummary: cumulative mean is the stable figure; count
    // gives weight; max is shown only when non-zero (it decays to 0 between
    // polls in SimpleMeterRegistry).
    if (typeof meter.mean === 'number') {
      var parts = ['mean ' + num(meter.mean) + unit];
      if (typeof meter.max === 'number' && meter.max > 0) {
        parts.push('max ' + num(meter.max) + unit);
      }
      if (typeof meter.count === 'number') {
        parts.push('n=' + meter.count);
      }
      return parts.join(' · ');
    }
    if (typeof meter.value === 'number') {
      return num(meter.value, 3);
    }
    if (typeof meter.count === 'number') {
      return String(meter.count);
    }
    // Unknown meter type fallback.
    return (meter.measurements || [])
      .map(function (m) {
        return m.statistic + ': ' + num(m.value, 3);
      })
      .join(', ');
  }

  var SEVERITY_WEIGHT = { error: 0, warning: 1 };

  function severityWeight(insight) {
    var weight = SEVERITY_WEIGHT[insight && insight.severity];
    // An unknown severity sorts after the two the server emits rather than
    // before them: a kind added later must not push errors down the panel.
    return typeof weight === 'number' ? weight : 2;
  }

  function occurrences(insight) {
    var evidence = (insight && insight.evidence) || {};
    return typeof evidence.occurrences === 'number' ? evidence.occurrences : 0;
  }

  function lastSeen(insight) {
    var evidence = (insight && insight.evidence) || {};
    return evidence.lastSeen || '';
  }

  // The order the panel shows findings in. Deliberately here and not in
  // InsightsService: the endpoint publishes a documented payload whose order
  // is part of its contract, and this is a display decision.
  //
  // Errors before warnings; then the most-reported first, because an error ten
  // users hit outranks one that happened once; then the most recent, so a
  // fresh finding beats a stale one of the same weight.
  function rank(insights) {
    return (insights || []).slice().sort(function (a, b) {
      var bySeverity = severityWeight(a) - severityWeight(b);
      if (bySeverity !== 0) {
        return bySeverity;
      }
      var byCount = occurrences(b) - occurrences(a);
      if (byCount !== 0) {
        return byCount;
      }
      var seenA = lastSeen(a);
      var seenB = lastSeen(b);
      return seenA > seenB ? -1 : seenA < seenB ? 1 : 0;
    });
  }

  // Identity of a finding across polls, so an opened row stays open while its
  // occurrence count keeps climbing. Built from the parts of the evidence that
  // correspond to what the server grouped on -- never from the summary, which
  // carries the count and so changes under the reader.
  function insightKey(insight) {
    var evidence = (insight && insight.evidence) || {};
    return [
      insight && insight.type,
      evidence.route,
      evidence.component,
      evidence.event,
      // The server groups a failure by its exception type as well, so without
      // this two different failures of the same handler are one key here: the
      // second would never be announced, and opening one row would open both.
      evidence.exception,
      evidence.queryKind,
      evidence.kind,
      evidence.source,
      evidence.frame
    ]
      .map(function (part) {
        return part == null ? '' : String(part);
      })
      .join('|');
  }

  function severityColor(insight) {
    if (insight.severity === 'error') {
      return 'var(--dev-tools-red-color,#e53935)';
    }
    if (insight.severity === 'warning') {
      return 'var(--dev-tools-yellow-color,#f9a825)';
    }
    return '#888';
  }

  // The one-line context under a summary: where the finding is and how it has
  // been behaving. The summary already says what happened, so this says which
  // route, which component or script, how many and how recently.
  function insightMeta(insight) {
    var evidence = insight.evidence || {};
    var parts = [];
    if (evidence.route) {
      parts.push(evidence.route);
    }
    if (evidence.component) {
      // The caption when the server collected one: '"Process return" Button'
      // is the component the reader is looking at, where 'Button' is a guess
      // among the five on the view.
      parts.push(
        evidence.componentCaption
          ? "'" + evidence.componentCaption + "' " + simpleName(evidence.component)
          : simpleName(evidence.component)
      );
    }
    if (evidence.frame) {
      parts.push(evidence.frame);
    } else if (evidence.source) {
      parts.push(evidence.source);
    }
    var count = occurrences(insight);
    if (count > 0) {
      parts.push(count + (count === 1 ? ' occurrence' : ' occurrences'));
    }
    if (evidence.lastSeen) {
      parts.push('last seen ' + clockTime(evidence.lastSeen));
    }
    return parts.join(' · ');
  }

  function detailRow(key, value) {
    return (
      '<div style="display:flex;gap:8px;padding:1px 0">' +
      '<span style="color:#888;min-width:120px;flex:none">' +
      esc(key) +
      '</span>' +
      '<span style="word-break:break-word">' +
      esc(typeof value === 'string' ? value : JSON.stringify(value)) +
      '</span>' +
      '</div>'
    );
  }

  // The evidence, the replay steps and the suggestion, exactly as the server
  // wrote them. Every insight kind carries all three.
  function insightDetail(insight) {
    var evidence = insight.evidence || {};
    var rows = Object.keys(evidence)
      .filter(function (key) {
        return evidence[key] !== null && evidence[key] !== undefined;
      })
      .map(function (key) {
        return detailRow(key, evidence[key]);
      })
      .join('');

    var replay = (insight.replay || [])
      .map(function (step) {
        return '<li style="margin:1px 0">' + esc(step) + '</li>';
      })
      .join('');

    return (
      '<div style="padding:6px 8px 10px 22px;font-size:12px;line-height:1.5">' +
      rows +
      (replay
        ? '<div style="margin-top:8px;color:#888">Replay</div>' +
          '<ol style="margin:2px 0 0;padding-left:18px">' +
          replay +
          '</ol>'
        : '') +
      (insight.suggestion
        ? '<div style="margin-top:8px;color:#888">Suggestion</div>' +
          '<div style="margin-top:2px">' +
          esc(insight.suggestion) +
          '</div>'
        : '') +
      '</div>'
    );
  }

  var ROW_BUTTON_STYLE =
    'flex:none;font:inherit;font-size:11px;padding:2px 6px;cursor:pointer;' +
    'background:none;border:1px solid rgba(128,128,128,.4);border-radius:4px;' +
    'color:inherit';

  function rowButton(action, index, label, title) {
    return (
      '<button data-action="' +
      action +
      '" data-index="' +
      index +
      '" title="' +
      esc(title) +
      '" style="' +
      ROW_BUTTON_STYLE +
      '">' +
      esc(label) +
      '</button>'
    );
  }

  function insightRow(insight, index, hidden) {
    var key = insightKey(insight);
    var open = !!expanded[key];
    // A folded-away finding is shown faded when the section is unfolded, so
    // that what is being ignored reads differently from what is not.
    return (
      '<div style="border-bottom:1px solid rgba(128,128,128,.15)' +
      (hidden ? ';opacity:.55' : '') +
      '">' +
      '<div data-action="toggle-insight" data-index="' +
      index +
      '" style="display:flex;align-items:flex-start;gap:8px;padding:7px 8px;cursor:pointer">' +
      '<span style="color:' +
      severityColor(insight) +
      ';line-height:1.4;flex:none">●</span>' +
      '<div style="flex:1;min-width:0">' +
      '<div style="line-height:1.4;word-break:break-word">' +
      esc(insight.summary) +
      '</div>' +
      '<div style="color:#888;font-size:11px;margin-top:2px;word-break:break-word">' +
      esc(insightMeta(insight)) +
      '</div>' +
      '</div>' +
      rowButton('copy-insight', index, 'Copy',
        'Copy this finding as JSON') +
      (isDismissed(insight)
        ? rowButton('restore-insight', index, 'Unhide',
            'Count this finding again')
        : rowButton('hide-insight', index, 'Hide',
            'Stop counting this finding; it stays under "hidden"')) +
      '<span style="flex:none;color:#888;width:12px;text-align:center">' +
      (open ? '▾' : '▸') +
      '</span>' +
      '</div>' +
      (open ? insightDetail(insight) : '') +
      '</div>'
    );
  }

  /**
   * The fold that the set-aside findings live behind, and its count. It says
   * which of the two reasons put them there, because "you hid this" and "this
   * stopped happening" are different things to know.
   */
  function hiddenToggle(count, byHand) {
    var noun = count === 1 ? 'finding' : 'findings';
    var what =
      byHand === count
        ? count + ' hidden ' + noun
        : byHand === 0
          ? count + ' ' + noun + ' gone quiet'
          : count + ' ' + noun + ' set aside (' + byHand + ' hidden, ' +
            (count - byHand) + ' gone quiet)';
    return (
      '<div data-action="toggle-hidden" style="display:flex;align-items:center;' +
      'gap:6px;padding:7px 12px;cursor:pointer;color:#888;font-size:11px">' +
      '<span style="width:12px;text-align:center">' +
      (showHidden ? '▾' : '▸') +
      '</span>' +
      '<span>' +
      esc(what) +
      '</span>' +
      '</div>'
    );
  }

  function emptyInsights(instrumentation) {
    var message =
      instrumentation === 'inactive'
        ? // Not necessarily the insights flag: no buffer is bound either when
          // it is off, or when it is on and both errors and requests are off,
          // and the panel cannot tell those apart from here.
          'Insights are not being collected. They need ' +
          'vaadin.observability.insights together with errors or requests ' +
          '(and client, for browser errors); check that none of those is off.'
        : 'No problems detected yet. Failed and over-budget interactions, ' +
          'data queries and browser errors show up here as they happen.';
    return (
      '<div style="padding:10px 12px;color:var(--dev-tools-text-color-secondary,#888)">' +
      esc(message) +
      '</div>'
    );
  }

  // Meters the server could not attribute to a route carry these instead of
  // one, and they are not routes: they sort last, below every real one.
  var ROUTE_UNKNOWN = '_unknown';
  var ROUTE_OTHER = '_other';

  function routeOf(meter) {
    var route = meter && meter.tags && meter.tags.route;
    return route == null ? null : String(route);
  }

  function pathSegments() {
    var path = (window.location && window.location.pathname) || '';
    return path.split('/').filter(function (segment) {
      return segment !== '';
    });
  }

  function isParameter(part) {
    return part.charAt(0) === ':';
  }

  // Flow's own rule for reading a modifier off a parameter segment, from
  // RouteFormat: a template may carry a regex, and when it does the modifier
  // sits in front of it - ':id?(\\d+)' as much as ':id?'. Testing the last
  // character alone would read every typed parameter as a required segment.
  function isOptional(part) {
    return (
      isParameter(part) &&
      (part.charAt(part.length - 1) === '?' || part.indexOf('?(') > 0)
    );
  }

  function isVarargs(part) {
    return (
      isParameter(part) &&
      (part.charAt(part.length - 1) === '*' || part.indexOf('*(') > 0)
    );
  }

  function segmentsOf(route) {
    return route.split('/').filter(function (part) {
      return part !== '';
    });
  }

  /**
   * Whether a Flow route template describes the path the browser is on.
   *
   * The tag is a template ('orders/:orderId'), the browser has a location
   * ('/orders/17'), and matching them is what lets the panel put the route
   * being worked on first. A parameter segment matches one segment, an
   * optional one at most one, and a varargs one the rest.
   *
   * An application served under a context path has that path in front of every
   * location and in none of the templates, so nothing matches and the groups
   * fall back to alphabetical. That is the whole cost of being wrong here.
   */
  function routeMatchesPath(route, segments) {
    if (route === null || route === ROUTE_UNKNOWN || route === ROUTE_OTHER) {
      return false;
    }
    var parts = segmentsOf(route);
    var at = 0;
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (isParameter(part)) {
        if (isVarargs(part)) {
          return true;
        }
        if (isOptional(part)) {
          if (at < segments.length) {
            at++;
          }
          continue;
        }
        if (at >= segments.length) {
          return false;
        }
        at++;
        continue;
      }
      if (segments[at] !== part) {
        return false;
      }
      at++;
    }
    return at === segments.length;
  }

  /**
   * The route the browser is on, as the meters name it: the matching template
   * with the fewest parameters, so a literal 'orders/new' wins over
   * 'orders/:id' when both match. Null when no meter's route describes this
   * location, which is the normal state of a page nothing has recorded yet.
   */
  function currentRoute(routes) {
    var segments = pathSegments();
    var best = null;
    var bestParams = -1;
    routes.forEach(function (route) {
      if (!routeMatchesPath(route, segments)) {
        return;
      }
      var params = segmentsOf(route).filter(isParameter).length;
      if (best === null || params < bestParams) {
        best = route;
        bestParams = params;
      }
    });
    return best;
  }

  /**
   * Meters grouped by the route they were recorded on, the route the browser
   * is on first: that is the group a developer is looking for, and scrolling
   * an alphabetical registry to find it is what this panel existed to make
   * someone do.
   *
   * Every other route follows alphabetically, the two sentinels after them
   * because they are not routes, and the meters carrying no route tag at all
   * last under a general heading - they are the application-wide ones, true
   * wherever the browser happens to be.
   */
  function groupByRoute(meters) {
    var byRoute = {};
    var general = [];
    meters.forEach(function (meter) {
      var route = routeOf(meter);
      if (route === null) {
        general.push(meter);
      } else {
        (byRoute[route] = byRoute[route] || []).push(meter);
      }
    });

    var routes = Object.keys(byRoute);
    var current = currentRoute(routes);
    routes.sort(function (a, b) {
      if (a === current) {
        return -1;
      }
      if (b === current) {
        return 1;
      }
      var sentinelA = a === ROUTE_UNKNOWN || a === ROUTE_OTHER;
      var sentinelB = b === ROUTE_UNKNOWN || b === ROUTE_OTHER;
      if (sentinelA !== sentinelB) {
        return sentinelA ? 1 : -1;
      }
      return a < b ? -1 : a > b ? 1 : 0;
    });

    var groups = routes.map(function (route) {
      return { route: route, current: route === current, meters: byRoute[route] };
    });
    if (general.length) {
      groups.push({ route: null, current: false, meters: general });
    }
    return groups;
  }

  function groupHeading(group) {
    var name =
      group.route === null
        ? 'General'
        : group.route === ROUTE_UNKNOWN
          ? 'Route not resolved'
          : group.route === ROUTE_OTHER
            ? 'Other routes'
            : // The root view's template is the empty string, and the rows no
              // longer carry the route tag to say so.
              group.route === ''
              ? 'Root'
              : group.route;
    return (
      '<div style="display:flex;align-items:baseline;gap:6px;margin-top:10px;' +
      'padding-bottom:2px;font-weight:600;border-bottom:1px solid rgba(128,128,128,.3)">' +
      '<span>' +
      esc(name) +
      '</span>' +
      (group.current
        ? '<span style="font-weight:400;font-size:11px;color:var(--lumo-primary-color,#1676f3)">' +
          'current page' +
          '</span>'
        : '') +
      '<span style="margin-left:auto;font-weight:400;font-size:11px;color:#888">' +
      esc(
        group.meters.length + (group.meters.length === 1 ? ' meter' : ' meters')
      ) +
      '</span>' +
      '</div>'
    );
  }

  function meterTable(group) {
    var rows = group.meters
      .map(function (meter) {
        // Inside a route group the route tag is the heading, so repeating it
        // on every row would be noise where the remaining tags are signal.
        var tagText = formatTags(meter.tags, group.route === null ? null : 'route');
        var nameCell =
          esc(meter.name) +
          (tagText
            ? '<div style="color:#888;font-size:11px">' + esc(tagText) + '</div>'
            : '');
        return (
          '<tr style="border-bottom:1px solid rgba(128,128,128,.15)">' +
          '<td style="padding:5px 8px;vertical-align:top;word-break:break-word">' +
          nameCell +
          '</td>' +
          '<td style="padding:5px 8px;vertical-align:top;white-space:nowrap;font-variant-numeric:tabular-nums">' +
          esc(formatMeterValue(meter)) +
          '</td>' +
          '<td style="padding:5px 8px;vertical-align:middle;width:84px">' +
          sparkline(history[meterKey(meter)]) +
          '</td>' +
          '</tr>'
        );
      })
      .join('');
    return (
      '<table style="border-collapse:collapse;width:100%">' +
      '<tbody>' +
      rows +
      '</tbody>' +
      '</table>'
    );
  }

  // ---- reading the meters as budgets ---------------------------------------
  //
  // The vitals, the key metrics and the slowest-things table all ask the same
  // question of the snapshot: what does this meter, across every tag value it
  // was recorded with, average - and is that within a common budget. The
  // budgets are the usual ones (Web Vitals' INP and LCP, and rules of thumb
  // for the server side), a baseline to read against rather than a rule.

  var TAG_INTERACTION = 'vaadin.interaction';
  var TAG_REQUEST_TYPE = 'vaadin.request.type';
  // Requests nobody made on purpose. Filtered out of the full meter list on
  // request, and never part of a budget.
  var NOISE_REQUEST_TYPES = ['heartbeat', 'push', 'static'];
  var NOISE_INTERACTIONS = ['poll'];
  var CLIENT_REQUEST = 'vaadin.client.request.duration';
  var CLIENT_RENDER = 'vaadin.client.render.duration';
  var CLICK_BUDGET_MS = 100;
  // How far apart, on the server's clock, a browser sample and the server's
  // record of an interaction may be and still describe the same click.
  var CLIENT_MATCH_MS = 5000;

  function matches(meter, name, tags) {
    if (!meter || meter.name !== name) {
      return false;
    }
    var have = meter.tags || {};
    return Object.keys(tags || {}).every(function (key) {
      return have[key] === tags[key];
    });
  }

  /**
   * One figure for a meter across all its tag values: the count-weighted mean
   * for timers and summaries, the sum for gauges and counters. Null when no
   * meter of that name is registered at all.
   */
  function aggregate(meters, name, tags) {
    var found = false;
    var count = 0;
    var total = 0;
    var value = null;
    (meters || []).forEach(function (meter) {
      if (!matches(meter, name, tags)) {
        return;
      }
      found = true;
      if (typeof meter.mean === 'number') {
        var n = typeof meter.count === 'number' ? meter.count : 0;
        count += n;
        total += meter.mean * n;
      } else if (typeof meter.value === 'number') {
        value = (value || 0) + meter.value;
      } else if (typeof meter.count === 'number') {
        count += meter.count;
      }
    });
    if (!found) {
      return null;
    }
    return { count: count, mean: count > 0 ? total / count : null, value: value };
  }

  function meanOf(meters, name, tags) {
    var stat = aggregate(meters, name, tags);
    return stat ? stat.mean : null;
  }

  function hasNumber(value) {
    return typeof value === 'number' && isFinite(value);
  }

  // A duration as the cards show it: a decimal while it is small enough for
  // one to matter, whole milliseconds above that.
  function ms(value) {
    if (!hasNumber(value)) {
      return '—';
    }
    return value >= 20 ? String(Math.round(value)) : value.toFixed(1);
  }

  function budgetText(budget) {
    return budget >= 1000 ? budget / 1000 + ' s' : budget + ' ms';
  }

  /**
   * How a figure reads against its budget. `fast` names the best case for
   * the one budget where being far under it is worth saying out loud.
   */
  function verdict(value, budget, fast) {
    if (!hasNumber(value)) {
      return null;
    }
    var ratio = value / budget;
    if (fast && ratio <= 0.25) {
      return { tone: 'good', label: fast };
    }
    if (ratio <= 1) {
      return { tone: 'good', label: 'Good' };
    }
    if (ratio <= 1.5) {
      return { tone: 'warn', label: 'Slightly slow' };
    }
    return { tone: 'bad', label: 'Over budget' };
  }

  function pill(judged, compact) {
    if (!judged) {
      return '';
    }
    var label = compact
      ? (judged.tone === 'good' ? '✓ Good' : '! Over')
      : judged.label;
    return '<span class="ok-pill ok-' + judged.tone + '">' + esc(label) + '</span>';
  }

  // A value on a track scaled to one and a half budgets, with a tick at the
  // budget, so that being over it is visible without being off the end.
  function budgetBar(value, budget, judged) {
    var scale = budget * 1.5;
    var fill = hasNumber(value) ? Math.min(value / scale, 1) * 100 : 0;
    return (
      '<div class="ok-track">' +
      '<div class="ok-fill' + (judged ? ' ok-' + judged.tone : '') +
      '" style="width:' + fill.toFixed(1) + '%"></div>' +
      '<div class="ok-tick" style="left:' + (100 / 1.5).toFixed(1) + '%"></div>' +
      '</div>'
    );
  }

  function tagSpec(tags) {
    return Object.keys(tags || {})
      .map(function (key) {
        return key.replace('vaadin.', '') + '=' + tags[key];
      })
      .join(', ');
  }

  function linkTo(tab, label) {
    return (
      '<span class="ok-link" data-action="tab" data-index="' + tab + '">' +
      esc(label) + '</span>'
    );
  }

  /**
   * The figures the vitals and the key metrics are built from, in the order
   * they are shown. Each note is HTML, escaped where it interpolates.
   */
  var VITALS = [
    {
      title: 'Click response',
      keyTitle: 'Click response time',
      keyNote: 'Round trip from user action to updated UI.',
      name: CLIENT_REQUEST,
      budget: CLICK_BUDGET_MS,
      scaleLabel: CLICK_BUDGET_MS + ' ms feels instant · ' + CLICK_BUDGET_MS + ' ms INP budget',
      fast: 'Instant',
      note: function (meters, stat) {
        return esc(
          'Every Vaadin interaction is a server round trip. This is your ' +
            "app's INP. Avg of " + stat.count + (stat.count === 1 ? ' click.' : ' clicks.')
        );
      }
    },
    {
      title: 'Server time per click',
      keyTitle: 'Server time per event',
      keyNote: 'Your listener and service code for one interaction.',
      name: 'vaadin.request.duration',
      tags: { 'vaadin.interaction': 'rpc' },
      budget: 50,
      note: function () {
        return esc(
          'Time spent in your listeners and services. The part you own, and ' +
            'the part that multiplies with users.'
        );
      }
    },
    {
      title: 'View navigation',
      keyTitle: 'View navigation',
      keyNote: 'Moving to a new route, including creating the view.',
      name: 'vaadin.request.duration',
      tags: { 'vaadin.interaction': 'navigation' },
      budget: 200,
      note: function (meters) {
        var lifecycle = meanOf(meters, 'vaadin.navigation');
        return (
          esc(
            'Route change incl. building the view.' +
              (hasNumber(lifecycle)
                ? ' ' + ms(lifecycle) + ' ms in the navigation lifecycle.'
                : '')
          ) +
          ' ' +
          linkTo(TAB_ANATOMY, 'See breakdown')
        );
      }
    },
    {
      title: 'Grid data fetch',
      keyTitle: 'Data fetch per page',
      keyNote: 'Backend query when a Grid or ComboBox loads rows.',
      name: 'vaadin.data.fetch.duration',
      budget: 100,
      unit: 'ms / page',
      note: function (meters) {
        var requested = meanOf(meters, 'vaadin.data.fetch.requested');
        var rows = meanOf(meters, 'vaadin.data.fetch.rows');
        var text = "Your data provider's query time.";
        if (hasNumber(requested) && hasNumber(rows)) {
          text +=
            ' Asked for ' + Math.round(requested) + ' rows, got ' +
            num(rows) + ' back on average.';
          if (rows < requested / 2) {
            text += " Dev data is tiny; prod data won't be.";
          }
        }
        return esc(text);
      }
    },
    {
      title: 'Page load (LCP)',
      keyTitle: 'Largest Contentful Paint',
      keyNote: 'Core Web Vital: when the main content becomes visible.',
      name: 'vaadin.client.web_vitals.lcp',
      budget: 2500,
      scaleLabel: '2.5 s Core Web Vitals',
      note: function (meters) {
        var fcp = meanOf(meters, 'vaadin.client.web_vitals.fcp');
        return esc(
          'When the main content is visible.' +
            (hasNumber(fcp) ? ' First paint at ' + ms(fcp) + ' ms.' : '')
        );
      }
    },
    {
      title: 'Server bootstrap',
      name: 'vaadin.request.duration',
      tags: { 'vaadin.request.type': 'bootstrap' },
      budget: 200,
      note: function () {
        return esc(
          'Creating the session and first UI on the server before anything ' +
            'is sent.'
        );
      }
    }
  ];

  // The first five: what the key-metrics list pins above the full one.
  var KEY_METRICS = VITALS.filter(function (def) {
    return !!def.keyTitle;
  });
  var VITAL_CLICK = VITALS[0];
  var VITAL_NAVIGATION = VITALS[2];

  function vitalCard(def, meters) {
    var stat = aggregate(meters, def.name, def.tags);
    var value = stat ? stat.mean : null;
    var judged = verdict(value, def.budget, def.fast);
    return (
      '<div class="ok-card' + (judged && judged.tone !== 'good' ? ' ok-card-warn' : '') + '">' +
      '<div class="ok-card-head"><span>' + esc(def.title) + '</span>' + pill(judged) + '</div>' +
      '<div class="ok-big ok-mono">' + esc(ms(value)) +
      '<span class="ok-unit">' + esc(def.unit || 'ms') + '</span></div>' +
      budgetBar(value, def.budget, judged) +
      '<div class="ok-scale"><span>0</span><span>' +
      esc(def.scaleLabel || budgetText(def.budget) + ' budget') +
      '</span></div>' +
      '<div class="ok-note">' +
      (hasNumber(value) ? def.note(meters, stat) : esc('No samples yet.')) +
      '</div>' +
      '</div>'
    );
  }

  function previewTile(title, measured, budget, within) {
    if (!hasNumber(measured)) {
      return (
        '<div class="ok-tile"><div class="ok-note ok-flush">' + esc(title) + '</div>' +
        '<div class="ok-mid ok-mono">—</div>' +
        '<div class="ok-note ok-flush">No samples yet.</div></div>'
      );
    }
    var total = measured + prodLatency;
    return (
      '<div class="ok-tile"><div class="ok-note ok-flush">' + esc(title) + '</div>' +
      '<div class="ok-mid ok-mono">' + esc(ms(total)) + '<span class="ok-unit">ms</span></div>' +
      '<div class="ok-note ok-flush">' +
      esc(total <= budget ? within : 'Over the ' + budget + ' ms INP budget') +
      '</div></div>'
    );
  }

  /**
   * What the two figures a user feels most would be with a real network in
   * between. The estimate is the honest one: measured time plus one round
   * trip, with nothing said about load.
   */
  function prodPreview(meters) {
    var buttons = LATENCIES.map(function (latency) {
      return (
        '<button class="ok-btn' + (latency === prodLatency ? ' on' : '') +
        '" data-action="latency" data-index="' + latency + '">' +
        esc(latency === 0 ? 'Localhost' : latency + ' ms') +
        '</button>'
      );
    }).join('');
    return (
      '<div class="ok-preview">' +
      '<div class="ok-card-head"><span>What will this feel like in production?</span></div>' +
      '<div class="ok-note ok-flush">On localhost the network costs ~0 ms. ' +
      "Pick a user's round-trip latency:</div>" +
      '<div class="ok-seg">' + buttons + '</div>' +
      '<div class="ok-tiles">' +
      previewTile('Typical click',
        meanOf(meters, VITAL_CLICK.name, VITAL_CLICK.tags),
        VITAL_CLICK.budget, 'Still feels instant') +
      previewTile('View navigation',
        meanOf(meters, VITAL_NAVIGATION.name, VITAL_NAVIGATION.tags),
        VITAL_NAVIGATION.budget, 'Within the ' + VITAL_NAVIGATION.budget + ' ms budget') +
      '</div>' +
      '<div class="ok-note">Estimate: measured time + one round trip. ' +
      'Server time also grows with concurrent users.</div>' +
      '</div>'
    );
  }

  function vitalsView(snapshot) {
    var meters = (snapshot && snapshot.meters) || [];
    var when = snapshot && snapshot.timestamp ? clockTime(snapshot.timestamp) : '';
    return (
      '<div class="ok-sec">' +
      '<div class="ok-h"><span>How your app feels right now</span>' +
      '<span class="ok-aside">' + esc('this session' + (when ? ' · updated ' + when : '')) + '</span></div>' +
      '<div class="ok-sub">Measured from real clicks in your browser and your ' +
      'server. Baselines are common budgets, not hard rules.</div>' +
      '<div class="ok-grid">' +
      VITALS.map(function (def) {
        return vitalCard(def, meters);
      }).join('') +
      '</div>' +
      prodPreview(meters) +
      '</div>'
    );
  }

  // ---- the anatomy of the last click ---------------------------------------

  // The browser collector's most recent sample of a meter, when it is close
  // enough in time to the interaction to be the same one. Null without the
  // collector: client metrics may be off.
  function browserSample(name, at) {
    var api = window.__vaadinMicrometer;
    if (!api || typeof api.latest !== 'function') {
      return null;
    }
    var sample;
    try {
      sample = api.latest(name);
    } catch (e) {
      return null;
    }
    if (!sample || !hasNumber(sample.valueMs)) {
      return null;
    }
    if (hasNumber(at) && hasNumber(sample.ts) &&
        Math.abs(sample.ts + clockSkew - at) > CLIENT_MATCH_MS) {
      return null;
    }
    return sample.valueMs;
  }

  function capitalized(text) {
    var value = String(text || 'interaction');
    return value.charAt(0).toUpperCase() + value.substring(1);
  }

  function interactionTitle(last) {
    var target = simpleName(last.component);
    if (target && last.caption) {
      target += ' "' + last.caption + '"';
    }
    var where = last.view || (last.route === '' ? 'Root' : last.route);
    return (
      capitalized(last.event) +
      (target ? ' on [' + target + ']' : '') +
      (where ? ' in [' + where + ']' : '')
    );
  }

  function legendItem(color, label, value, note) {
    return (
      '<div><div class="ok-legend-label"><span class="ok-dot" style="background:' +
      color + '"></span>' + esc(label) + '</div>' +
      '<div class="ok-mid ok-mono ok-small">' +
      esc(hasNumber(value) ? ms(value) + ' ms' : 'n/a') + '</div>' +
      '<div class="ok-note ok-flush">' + esc(note) + '</div></div>'
    );
  }

  var SEGMENT_NETWORK = '#9db7ea';
  var SEGMENT_SERVER = '#2f64c7';
  var SEGMENT_RENDER = '#6c4fbd';

  function segment(color, value, total) {
    if (!hasNumber(value) || value <= 0 || total <= 0) {
      return '';
    }
    return (
      '<div style="background:' + color + ';width:' +
      ((value / total) * 100).toFixed(1) + '%"></div>'
    );
  }

  /**
   * The last interaction, split into the parts a developer can act on: the
   * wire, their own code and the browser. The server part is the time the
   * listeners took; the round trip and the render come from the browser
   * collector, and are left out rather than guessed when it is not there.
   */
  function lastInteractionView(last) {
    if (!last) {
      return (
        '<div class="ok-sec"><div class="ok-caps">Your last interaction</div>' +
        '<div class="ok-note">Click something in the application and it is ' +
        'broken down here. Needs vaadin.observability.insights together with ' +
        'errors or requests.</div></div>'
      );
    }
    var server = hasNumber(last.serverMs) ? last.serverMs : null;
    var roundTrip = browserSample(CLIENT_REQUEST, last.timestamp);
    var render = browserSample(CLIENT_RENDER, last.timestamp);
    var network = hasNumber(roundTrip) && hasNumber(server)
      ? Math.max(0, roundTrip - server)
      : null;
    var total = hasNumber(roundTrip)
      ? roundTrip + (render || 0)
      : (server || 0) + (render || 0);
    var judged = verdict(total, CLICK_BUDGET_MS, 'Instant');
    var parts = (network || 0) + (server || 0) + (render || 0);
    var invocations = last.invocations || 1;
    return (
      '<div class="ok-sec">' +
      '<div class="ok-caps">Your last interaction' +
      (last.outcome === 'error' ? ' · failed' : '') + '</div>' +
      '<div class="ok-title">' + esc(interactionTitle(last)) + '</div>' +
      '<div class="ok-total ok-mono">' + esc(ms(total)) +
      '<span class="ok-unit">ms</span>' +
      (judged
        ? '<span class="ok-pill ok-' + judged.tone + ' ok-inline">' +
          esc(judged.label + ' · INP budget ' + CLICK_BUDGET_MS + ' ms') + '</span>'
        : '') +
      '</div>' +
      '<div class="ok-stack">' +
      segment(SEGMENT_NETWORK, network, parts) +
      segment(SEGMENT_SERVER, server, parts) +
      segment(SEGMENT_RENDER, render, parts) +
      '</div>' +
      '<div class="ok-legend">' +
      legendItem(SEGMENT_NETWORK, 'Network & transfer', network,
        hasNumber(roundTrip)
          ? '~0 on localhost. This is what grows in prod.'
          : 'Needs client metrics (vaadin.observability.client).') +
      legendItem(SEGMENT_SERVER, 'Server (your code)', server,
        'Listeners, services, DB calls.') +
      legendItem(SEGMENT_RENDER, 'Browser render', render,
        'Applying changes to the DOM.') +
      '</div>' +
      '<div class="ok-note">' +
      esc(
        invocations + (invocations === 1 ? ' event' : ' events') +
          ' handled in one server round trip. More than one round trip per ' +
          'click is a common prod latency multiplier.'
      ) +
      '</div>' +
      '</div>'
    );
  }

  /**
   * The rows of "slowest things this session". Each is a server-side cost a
   * developer can go and look at, with a budget to be read against and a
   * tip for when it is over it.
   */
  var SLOWEST = [
    {
      title: 'View navigation',
      name: 'vaadin.request.duration',
      tags: { 'vaadin.interaction': 'navigation' },
      budget: 200,
      sub: function (meters, mean) {
        var lifecycle = meanOf(meters, 'vaadin.navigation');
        if (!hasNumber(lifecycle)) {
          return '';
        }
        return ms(lifecycle) + ' ms lifecycle · ' +
          ms(Math.max(0, mean - lifecycle)) + ' ms other server work';
      },
      tip: function (meters, mean) {
        var lifecycle = meanOf(meters, 'vaadin.navigation');
        return hasNumber(lifecycle) && lifecycle < mean / 2
          ? 'Most of the time is outside the navigation lifecycle. Check the ' +
              'view constructor and anything loaded eagerly in beforeEnter.'
          : 'Most of the time is in the navigation lifecycle. Check ' +
              'beforeEnter, afterNavigation and the observers on the route.';
      }
    },
    {
      title: 'Grid data fetch',
      name: 'vaadin.data.fetch.duration',
      budget: 100,
      sub: function (meters) {
        var requested = meanOf(meters, 'vaadin.data.fetch.requested');
        return hasNumber(requested)
          ? Math.round(requested) + ' rows requested per page'
          : 'per page';
      },
      tip: function () {
        return 'Check the query behind the data provider: an index, a join ' +
          'fetched eagerly, or a count that runs on every page.';
      }
    },
    {
      title: 'Grid size query',
      name: 'vaadin.data.count.duration',
      budget: 100,
      sub: function () {
        return 'count queries';
      },
      tip: function () {
        return 'A count runs whenever the filter changes. Consider an estimate ' +
          'or a lazy data view with an undefined size.';
      }
    },
    {
      title: 'Initial page request',
      name: 'vaadin.request.duration',
      tags: { 'vaadin.request.type': 'bootstrap' },
      budget: 200,
      sub: function () {
        return 'bootstrap';
      },
      tip: function () {
        return 'Check what runs while the session and first UI are created: ' +
          'UI init listeners, and the first view built with them.';
      }
    },
    {
      title: 'Component events (RPC)',
      name: 'vaadin.rpc.duration',
      budget: 50,
      sub: function () {
        return 'clicks, value changes';
      },
      tip: function () {
        return 'A listener is doing slow work on the request thread. Move it ' +
          'to a background task and push the result.';
      }
    },
    {
      title: 'Session lock wait',
      name: 'vaadin.session.lock.wait',
      budget: 50,
      sub: function () {
        return 'requests queued behind one another';
      },
      tip: function () {
        return 'Something holds the session lock for long: a slow listener, ' +
          'or a background thread inside ui.access.';
      }
    },
    {
      title: 'Background tasks',
      name: 'vaadin.executor.task',
      budget: 100,
      sub: function () {
        return 'executor';
      },
      tip: function () {
        return 'Background work is slow. It does not block the user, but ' +
          'whatever it pushes arrives late.';
      }
    }
  ];

  function slowestRows(meters) {
    return SLOWEST.map(function (def) {
      var stat = aggregate(meters, def.name, def.tags);
      return { def: def, stat: stat };
    })
      .filter(function (row) {
        return row.stat && hasNumber(row.stat.mean) && row.stat.count > 0;
      })
      .sort(function (a, b) {
        return b.stat.mean - a.stat.mean;
      });
  }

  function slowestView(meters) {
    var rows = slowestRows(meters);
    if (rows.length === 0) {
      return (
        '<div class="ok-sec ok-rule"><div class="ok-h"><span>Slowest things this session</span></div>' +
        '<div class="ok-note">Nothing measured yet.</div></div>'
      );
    }
    var body = rows.map(function (row) {
      var mean = row.stat.mean;
      var judged = verdict(mean, row.def.budget);
      var fill = Math.min(mean / row.def.budget, 1) * 100;
      var sub = row.def.sub(meters, mean);
      return (
        '<tr><td><div>' + esc(row.def.title) + '</div>' +
        (sub ? '<div class="ok-note ok-flush">' + esc(sub) + '</div>' : '') + '</td>' +
        '<td class="ok-mono ok-nowrap">' + esc(num(mean, 1) + ' ms') + '</td>' +
        '<td class="ok-mono">' + esc(row.stat.count) + '</td>' +
        '<td style="width:90px"><div class="ok-track"><div class="ok-fill' +
        (judged && judged.tone !== 'good' ? ' ok-warn' : '') +
        '" style="width:' + fill.toFixed(1) + '%"></div></div></td></tr>'
      );
    }).join('');

    var over = rows.filter(function (row) {
      return row.stat.mean > row.def.budget;
    })[0];
    var tip = over
      ? '<div class="ok-tip"><div class="ok-card-head"><span>' +
        esc('Tip: ' + over.def.title.toLowerCase() + ' is over its ' +
          budgetText(over.def.budget) + ' budget') +
        '</span></div><div class="ok-note ok-flush">' +
        esc(over.def.tip(meters, over.stat.mean)) + '</div></div>'
      : '';

    return (
      '<div class="ok-sec ok-rule">' +
      '<div class="ok-h"><span>Slowest things this session</span>' +
      '<span class="ok-aside">sorted by mean time</span></div>' +
      '<table class="ok-table"><thead><tr><th>What</th><th>Mean</th>' +
      '<th>Count</th><th>Vs budget</th></tr></thead><tbody>' +
      body +
      '</tbody></table>' +
      tip +
      '</div>'
    );
  }

  // ---- the key metrics -----------------------------------------------------

  function keyMetricsView(meters, total) {
    var rows = KEY_METRICS.map(function (def) {
      var value = meanOf(meters, def.name, def.tags);
      var judged = verdict(value, def.budget);
      var spec = tagSpec(def.tags);
      return (
        '<div class="ok-key">' +
        '<div><div class="ok-strong">' + esc(def.keyTitle) + '</div>' +
        '<div class="ok-note ok-flush">' + esc(def.keyNote) + '</div>' +
        '<div class="ok-note ok-flush ok-mono">' +
        esc(def.name + (spec ? ' · ' + spec : '')) + '</div></div>' +
        '<div class="ok-key-value ok-mono">' +
        esc(hasNumber(value) ? ms(value) + ' ms' : '—') + '</div>' +
        '<div class="ok-key-badge">' + pill(judged, true) +
        '<div>' + esc('< ' + budgetText(def.budget)) + '</div></div>' +
        '</div>'
      );
    }).join('');
    return (
      '<div class="ok-sec">' +
      '<div class="ok-h"><span>Key metrics</span><span class="ok-aside">' +
      esc(KEY_METRICS.length + ' of ' + total + ' · pinned by Vaadin') +
      '</span></div>' +
      rows +
      '</div>'
    );
  }

  function isIdle(meter) {
    return !(
      (typeof meter.count === 'number' && meter.count > 0) ||
      (typeof meter.value === 'number' && meter.value !== 0) ||
      (meter.measurements || []).some(function (m) {
        return m.value !== 0;
      })
    );
  }

  function isNoise(meter) {
    var tags = meter.tags || {};
    return (
      NOISE_REQUEST_TYPES.indexOf(tags[TAG_REQUEST_TYPE]) >= 0 ||
      NOISE_INTERACTIONS.indexOf(tags[TAG_INTERACTION]) >= 0
    );
  }

  function chip(action, on, label) {
    return (
      '<button class="ok-chip' + (on ? ' on' : '') + '" data-action="' + action +
      '">' + esc((on ? '✓ ' : '') + label) + '</button>'
    );
  }

  var STYLE = [
    '.ok-root{font:13px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;' +
      '--ok-muted:var(--dev-tools-text-color-secondary,#888);' +
      '--ok-line:rgba(128,128,128,.25);--ok-blue:var(--lumo-primary-color,#1676f3);' +
      '--ok-good:#2e7d32;--ok-warn:#d9730d;--ok-bad:#d32f2f}',
    '.ok-mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}',
    '.ok-tabs{display:flex;gap:2px;padding:0 10px;border-bottom:1px solid var(--ok-line)}',
    '.ok-tab{font:inherit;background:none;border:0;border-bottom:2px solid transparent;' +
      'padding:8px 10px;cursor:pointer;color:var(--ok-muted)}',
    '.ok-tab.on{color:inherit;border-bottom-color:var(--ok-blue);font-weight:600}',
    '.ok-sec{padding:12px 14px}',
    '.ok-rule{border-top:1px solid var(--ok-line)}',
    '.ok-h{display:flex;align-items:baseline;gap:8px;font-weight:600;font-size:14px}',
    '.ok-aside{margin-left:auto;font-weight:400;font-size:11px;color:var(--ok-muted);white-space:nowrap}',
    '.ok-sub{color:var(--ok-muted);font-size:12px;margin-top:2px}',
    '.ok-strong{font-weight:600}',
    '.ok-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:10px;margin-top:12px}',
    '.ok-card{border:1px solid var(--ok-line);border-radius:6px;padding:10px 12px}',
    '.ok-card-warn{background:rgba(217,115,13,.07);border-color:rgba(217,115,13,.45)}',
    '.ok-card-head{display:flex;align-items:center;gap:6px;font-weight:600;font-size:12px}',
    '.ok-pill{margin-left:auto;font-size:10px;font-weight:600;padding:1px 7px;border-radius:9px;white-space:nowrap}',
    '.ok-pill.ok-inline{margin-left:4px;font-family:system-ui,sans-serif}',
    '.ok-pill.ok-good{color:var(--ok-good);background:rgba(46,125,50,.13)}',
    '.ok-pill.ok-warn{color:var(--ok-warn);background:rgba(217,115,13,.15)}',
    '.ok-pill.ok-bad{color:var(--ok-bad);background:rgba(211,47,47,.13)}',
    '.ok-big{font-size:20px;font-weight:600;margin:6px 0 4px}',
    '.ok-mid{font-size:15px;font-weight:600;margin:2px 0}',
    '.ok-small{font-size:13px}',
    '.ok-total{font-size:26px;font-weight:600;display:flex;align-items:baseline;gap:4px;margin-top:4px}',
    '.ok-unit{font-size:11px;font-weight:400;color:var(--ok-muted);margin-left:6px}',
    '.ok-track{position:relative;height:4px;border-radius:2px;background:rgba(128,128,128,.18)}',
    '.ok-fill{position:absolute;left:0;top:0;bottom:0;border-radius:2px;background:var(--ok-blue)}',
    // One selector per rule: each is scoped by prefixing the panel's tag.
    '.ok-fill.ok-warn{background:var(--ok-warn)}',
    '.ok-fill.ok-bad{background:var(--ok-warn)}',
    '.ok-tick{position:absolute;top:-3px;width:1px;height:10px;background:var(--ok-muted)}',
    '.ok-scale{display:flex;justify-content:space-between;font-size:10px;color:var(--ok-muted);margin-top:4px}',
    '.ok-note{font-size:11px;color:var(--ok-muted);margin-top:8px}',
    '.ok-flush{margin-top:1px}',
    '.ok-link{color:var(--ok-blue);text-decoration:underline;cursor:pointer}',
    '.ok-preview{margin-top:14px;border:1px solid rgba(22,118,243,.25);background:rgba(22,118,243,.06);' +
      'border-radius:6px;padding:12px}',
    '.ok-seg{display:flex;flex-wrap:wrap;gap:6px;margin:10px 0}',
    '.ok-btn{font:inherit;font-size:12px;padding:5px 12px;border:1px solid var(--ok-line);border-radius:4px;' +
      'background:none;color:inherit;cursor:pointer}',
    '.ok-btn.on{background:var(--ok-blue);border-color:var(--ok-blue);color:#fff;font-weight:600}',
    '.ok-tiles{display:grid;grid-template-columns:1fr 1fr;gap:8px}',
    '.ok-tile{background:rgba(128,128,128,.08);border-radius:4px;padding:8px 10px}',
    '.ok-caps{font-size:10px;letter-spacing:.06em;text-transform:uppercase;color:var(--ok-muted)}',
    '.ok-title{font-weight:600;font-size:15px;margin-top:2px;word-break:break-word}',
    '.ok-stack{display:flex;height:16px;border-radius:3px;overflow:hidden;margin:12px 0 8px;' +
      'background:rgba(128,128,128,.12)}',
    '.ok-legend{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;font-size:11px}',
    '.ok-legend-label{font-weight:600}',
    '.ok-dot{display:inline-block;width:8px;height:8px;border-radius:2px;margin-right:5px}',
    '.ok-table{width:100%;border-collapse:collapse;margin-top:8px;font-size:12px}',
    '.ok-table th{text-align:left;font-weight:400;font-size:10px;letter-spacing:.06em;text-transform:uppercase;' +
      'color:var(--ok-muted);padding:4px 6px;border-bottom:1px solid var(--ok-line)}',
    '.ok-table td{padding:8px 6px;border-bottom:1px solid var(--ok-line);vertical-align:middle}',
    '.ok-nowrap{white-space:nowrap}',
    '.ok-tip{margin-top:12px;border:1px solid rgba(217,115,13,.45);background:rgba(217,115,13,.07);' +
      'border-radius:6px;padding:10px 12px}',
    '.ok-key{display:grid;grid-template-columns:1fr auto 76px;gap:10px;align-items:center;' +
      'padding:9px 0;border-bottom:1px solid var(--ok-line)}',
    '.ok-key-value{font-size:14px;white-space:nowrap}',
    '.ok-key-badge{text-align:right;font-size:10px;color:var(--ok-muted)}',
    '.ok-chips{display:flex;gap:6px;margin-left:auto}',
    '.ok-chip{font:inherit;font-size:11px;padding:2px 9px;border-radius:10px;border:1px solid var(--ok-line);' +
      'background:none;color:var(--ok-muted);cursor:pointer}',
    '.ok-chip.on{border-color:var(--ok-blue);color:var(--ok-blue);background:rgba(22,118,243,.08)}',
    '.ok-footer{display:flex;justify-content:space-between;padding:10px 14px;' +
      'border-top:1px solid var(--ok-line);font-size:11px;color:var(--ok-muted)}'
  ]
    .map(function (rule) {
      return PANEL_TAG + ' ' + rule;
    })
    .join('');

  // Which findings have already been announced, keyed by the same grouping key
  // the rows are identified by, so one problem notifies once however many times
  // it recurs or however often the payload is polled.
  var announced = {};
  // When this page started, on the browser's clock. A finding older than this
  // is not news: the server's buffers outlive a reload, so the first payload
  // after one describes what was already there. Younger ones are announced,
  // including the ones raised while the page was loading - a slow query on the
  // landing view is exactly the kind of finding worth hearing about.
  var pageStart = Date.now();

  /**
   * Whether a finding is new enough to be worth announcing, measured against
   * the moment this page started.
   *
   * Both timestamps come from different clocks - `firstSeen` from the server,
   * `pageStart` from this browser - so the payload's own `generated` is used to
   * translate between them. Without it a browser a minute behind its server
   * would announce the whole buffer, and one a minute ahead would announce
   * nothing for a minute.
   */
  function raisedAfterPageStart(insight, payload) {
    var firstSeen = Date.parse(
      ((insight && insight.evidence) || {}).firstSeen || ''
    );
    if (isNaN(firstSeen)) {
      // Nothing to place it by. Treat it as pre-existing rather than announce
      // a finding that may be hours old.
      return false;
    }
    var generated = Date.parse((payload && payload.generated) || '');
    var skew = isNaN(generated) ? 0 : generated - Date.now();
    return firstSeen >= pageStart + skew;
  }

  /**
   * Announces findings the payload did not have before, and that this page is
   * old enough to be responsible for.
   *
   * Copilot's plugin API (send + addPanel) exposes no notification of its own,
   * so the announcement is a 'log' event on its event bus - the same event
   * Copilot emits for its own log lines. It works with the log panel closed:
   * the bus buffers an event of a type nothing is listening for and replays it
   * to the first listener that subscribes, which is what the log panel does
   * when it opens.
   *
   * Not relayed through the server, though a server message would be queued
   * and replayed the same way: Copilot offers a server message to the event
   * bus and then, if no listener claimed it, to every open panel, and the log
   * panel takes 'log' from both - so a relayed announcement is logged twice.
   *
   * Best-effort by design: a Copilot that drops this costs the developer a
   * notification, never the panel.
   */
  function announce(payload) {
    var fresh = [];
    ((payload && payload.insights) || []).forEach(function (insight) {
      var key = insightKey(insight);
      if (announced[key]) {
        return;
      }
      announced[key] = true;
      // Hidden by hand means "stop telling me about this", and a line in the
      // Copilot log is telling them about it. Staleness is not checked: a
      // finding old enough to be quiet cannot be one raised since this page
      // loaded.
      if (raisedAfterPageStart(insight, payload) && !isDismissed(insight)) {
        fresh.push(insight);
      }
    });
    var bus = eventBus();
    if (!bus || !bus.emit) {
      return;
    }
    rank(fresh).forEach(function (insight) {
      var message = 'Observability: ' + insight.summary;
      if (message.length > MAX_LOG_MESSAGE) {
        message = message.substring(0, MAX_LOG_MESSAGE) + '…';
      }
      try {
        bus.emit(EVENT_LOG, {
          type: insight.severity === 'error' ? 'error' : 'warning',
          message: message
        });
      } catch (e) {
        // A Copilot that does not take this is not a reason to stop watching,
        // and there is nowhere better to report it to.
      }
    });
  }

  // A server message, from the event bus or from the open panel. Both paths
  // land here so the state is updated once however it arrived.
  function accept(command, data) {
    if (command === COMMAND_METRICS) {
      // Copilot queues a message nothing claimed and replays it to a panel
      // when one opens, so the snapshot pushed at connect time - before this
      // script had registered anything - arrives late and stale. Taking it
      // would undo a fresher poll until the next one.
      if (latest && data && data.timestamp < latest.timestamp) {
        return true;
      }
      latest = data;
      if (data && typeof data.timestamp === 'number') {
        clockSkew = data.timestamp - Date.now();
      }
      recordHistory(latest.meters);
    } else if (command === COMMAND_INSIGHTS_DATA) {
      if (
        latestInsights &&
        data &&
        Date.parse(data.generated) < Date.parse(latestInsights.generated)
      ) {
        return true;
      }
      latestInsights = data;
      announce(latestInsights);
    } else {
      return false;
    }
    if (panel) {
      panel.render();
    }
    return true;
  }

  var ticks = 0;

  // Insights are polled whether or not the panel is open - that is what makes
  // a notification possible - and the meters only while it is.
  function poll() {
    if (!copilot) {
      return;
    }
    var open = !!panel;
    if (open) {
      copilot.send(COMMAND_REFRESH, {});
    }
    if (open || ticks % BACKGROUND_EVERY === 0) {
      copilot.send(COMMAND_INSIGHTS, {});
    }
    ticks++;
  }

  class ObservabilityMetricsPanel extends HTMLElement {
    connectedCallback() {
      this.style.display = 'block';
      this.style.height = '100%';
      this.style.overflow = 'auto';
      if (!this._wired) {
        this._wired = true;
        // One delegated listener rather than inline handlers, so a re-render
        // of either section cannot leave a live handler behind.
        this.addEventListener('click', (event) => this.handleClick(event));
      }
      panel = this;
      this.render();
      poll();
    }

    disconnectedCallback() {
      if (panel === this) {
        panel = null;
      }
    }

    // Copilot's panel manager calls this on its panel content element (the
    // method is provided by its internal BasePanel). We position the panel
    // explicitly, so there is nothing to recompute - just satisfy the contract.
    requestLayoutUpdate() {
      return Promise.resolve();
    }

    // Called by Copilot for every server message that reached the panel. The
    // module's event-bus listener normally claims ours first; this is what
    // answers when there is no event bus to listen on.
    handleMessage(message) {
      return !!message && accept(message.command, message.data);
    }

    handleClick(event) {
      var target = event.target && event.target.closest
        ? event.target.closest('[data-action]')
        : null;
      if (!target || !this.contains(target)) {
        return;
      }
      var action = target.getAttribute('data-action');
      if (action === 'tab') {
        activeTab = target.getAttribute('data-index');
        this.renderTabs();
        return;
      }
      if (action === 'latency') {
        prodLatency = Number(target.getAttribute('data-index')) || 0;
        this.renderVitals();
        return;
      }
      if (action === 'toggle-idle') {
        hideIdle = !hideIdle;
        this.renderMeters();
        return;
      }
      if (action === 'toggle-noise') {
        hideNoise = !hideNoise;
        this.renderMeters();
        return;
      }
      if (action === 'toggle-hidden') {
        showHidden = !showHidden;
        this.renderInsights(true);
        return;
      }
      var insight = (this._ranked || [])[Number(target.getAttribute('data-index'))];
      if (!insight) {
        return;
      }
      if (action === 'toggle-insight') {
        var key = insightKey(insight);
        if (expanded[key]) {
          delete expanded[key];
        } else {
          expanded[key] = true;
        }
        this.renderInsights(true);
      } else if (action === 'hide-insight') {
        dismiss(insight);
        this.renderInsights(true);
      } else if (action === 'restore-insight') {
        restore(insight);
        this.renderInsights(true);
      } else if (action === 'copy-insight') {
        // The insight JSON is built to travel - into an issue, into an AI
        // agent with codebase access - so the shortest path out of the panel
        // is the whole finding, not the summary line.
        var json = JSON.stringify(insight, null, 2);
        var done = () => {
          target.textContent = 'Copied';
          setTimeout(() => {
            target.textContent = 'Copy';
          }, 1200);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(json).then(done, () => {
            target.textContent = 'Failed';
          });
        } else {
          target.textContent = 'Failed';
        }
      }
    }

    render() {
      if (!this._insightsEl) {
        this.innerHTML =
          '<style>' + STYLE + '</style>' +
          '<div class="ok-root">' +
          '<div data-region="tabs"></div>' +
          '<div data-region="vitals"></div>' +
          '<div data-region="anatomy"></div>' +
          '<div data-region="metrics-tab">' +
          '<div data-region="key"></div>' +
          '<div data-region="metrics"></div>' +
          '</div>' +
          '<div data-region="insights"></div>' +
          '<div data-region="footer"></div>' +
          '</div>';
        this._tabsEl = this.querySelector('[data-region="tabs"]');
        this._vitalsEl = this.querySelector('[data-region="vitals"]');
        this._anatomyEl = this.querySelector('[data-region="anatomy"]');
        this._metricsTabEl = this.querySelector('[data-region="metrics-tab"]');
        this._keyEl = this.querySelector('[data-region="key"]');
        this._metersEl = this.querySelector('[data-region="metrics"]');
        this._insightsEl = this.querySelector('[data-region="insights"]');
        this._footerEl = this.querySelector('[data-region="footer"]');
      }
      this.renderInsights();
      this.renderMeters();
      this.renderVitals();
      this.renderAnatomy();
      this.renderTabs();
    }

    // Every tab is kept rendered and only the chosen one shown, so switching
    // is instant and the findings keep the rows the developer opened.
    renderTabs() {
      var current = activeTab || TAB_VITALS;
      var live = this._liveCount || 0;
      var total = ((latest && latest.meters) || []).length;
      this._tabsEl.innerHTML =
        '<div class="ok-tabs">' +
        TABS.map(function (tab) {
          var label =
            tab.key === TAB_FINDINGS && live > 0
              ? tab.label + ' (' + live + ')'
              : tab.label;
          return (
            '<button class="ok-tab' + (tab.key === current ? ' on' : '') +
            '" data-action="tab" data-index="' + tab.key + '">' +
            esc(label) +
            '</button>'
          );
        }).join('') +
        '</div>';
      this._vitalsEl.hidden = current !== TAB_VITALS;
      this._anatomyEl.hidden = current !== TAB_ANATOMY;
      this._metricsTabEl.hidden = current !== TAB_METRICS;
      this._insightsEl.hidden = current !== TAB_FINDINGS;

      var footer = current === TAB_VITALS || current === TAB_ANATOMY;
      this._footerEl.hidden = !footer;
      this._footerEl.innerHTML = footer
        ? '<div class="ok-footer">' +
          linkTo(TAB_METRICS, '‹ All metrics (' + total + (total === 1 ? ' meter)' : ' meters)')) +
          '<span>' + esc(latest && latest.timestamp ? 'updated ' + clockTime(latest.timestamp) : '') +
          '</span></div>'
        : '';
    }

    renderVitals() {
      this._vitalsEl.innerHTML = vitalsView(latest);
    }

    renderAnatomy() {
      this._anatomyEl.innerHTML =
        lastInteractionView(latest && latest.lastInteraction) +
        slowestView((latest && latest.meters) || []);
    }

    renderInsights(force) {
      var insights = (latestInsights && latestInsights.insights) || [];
      var instrumentation = latestInsights && latestInsights.instrumentation;
      this._ranked = rank(insights);

      // Indices into _ranked rather than the findings themselves, because
      // that is what a row's data-index resolves against when it is clicked.
      var live = [];
      var quiet = [];
      var byHand = 0;
      this._ranked.forEach(function (insight, index) {
        if (!isHidden(insight)) {
          live.push(index);
          return;
        }
        quiet.push(index);
        if (isDismissed(insight)) {
          byHand++;
        }
      });

      this._liveCount = live.length;
      // Decided by the first payload that reaches the panel, and never again:
      // once the developer has picked a tab, that is theirs.
      if (activeTab === null && latestInsights) {
        activeTab = live.length > 0 ? TAB_FINDINGS : TAB_VITALS;
      }

      // Rewritten only when something actually changed. The panel polls every
      // three seconds, and rebuilding this section on every poll would close
      // whatever row was open and drop the selection of anyone mid-copy.
      //
      // The partition is part of the signature, not just the payload: a
      // finding going quiet changes the panel without the payload changing at
      // all, and hiding one changes it without the server being involved.
      var signature = JSON.stringify([
        !!latestInsights,
        instrumentation,
        this._ranked,
        Object.keys(expanded).sort(),
        live,
        quiet,
        showHidden
      ]);
      if (!force && signature === this._insightsSignature) {
        return;
      }
      this._insightsSignature = signature;

      var ranked = this._ranked;
      var rows = function (indices, hidden) {
        return indices
          .map(function (index) {
            return insightRow(ranked[index], index, hidden);
          })
          .join('');
      };

      var header =
        '<div style="display:flex;align-items:center;gap:6px;padding:8px 12px 6px;' +
        'font-weight:600">' +
        (live.length === 0
          ? 'Insights'
          : esc(
              live.length +
                (live.length === 1 ? ' finding' : ' findings') +
                ' need attention'
            )) +
        '</div>';

      var body;
      if (live.length > 0) {
        body = rows(live, false);
      } else if (!latestInsights) {
        // Before the first payload there is no answer yet, and "no problems
        // detected" would be one.
        body =
          '<div style="padding:10px 12px;color:var(--dev-tools-text-color-secondary,#888)">' +
          'Waiting for the first snapshot…' +
          '</div>';
      } else if (quiet.length > 0) {
        // Findings exist; none is being counted. Saying "no problems
        // detected" here would be a claim the panel's own fold contradicts.
        body =
          '<div style="padding:10px 12px;color:var(--dev-tools-text-color-secondary,#888)">' +
          'Nothing needs attention right now.' +
          '</div>';
      } else {
        body = emptyInsights(instrumentation);
      }

      this._insightsEl.innerHTML =
        header +
        body +
        (quiet.length > 0
          ? hiddenToggle(quiet.length, byHand) +
            (showHidden ? rows(quiet, true) : '')
          : '');
      // The findings tab carries the count, which this may just have changed.
      this.renderTabs();
    }

    renderMeters() {
      var all = ((latest && latest.meters) || []).slice().sort(function (a, b) {
        return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
      });
      this._keyEl.innerHTML = keyMetricsView(all, all.length);

      var idle = 0;
      var noise = 0;
      var meters = all.filter(function (meter) {
        if (hideNoise && isNoise(meter)) {
          noise++;
          return false;
        }
        if (hideIdle && isIdle(meter)) {
          idle++;
          return false;
        }
        return true;
      });
      var hidden = [];
      if (idle > 0) {
        hidden.push(idle + ' idle');
      }
      if (noise > 0) {
        hidden.push(noise + ' as noise');
      }

      var header =
        '<div class="ok-sec ok-rule" style="padding-bottom:0">' +
        '<div class="ok-h"><span>All meters</span>' +
        '<span class="ok-chips">' +
        chip('toggle-idle', hideIdle, 'Hide idle') +
        chip('toggle-noise', hideNoise, 'Hide heartbeat & static') +
        '</span></div>' +
        '<div class="ok-sub">' +
        esc(
          'Showing ' + meters.length + ' of ' + all.length +
            (all.length === 1 ? ' meter' : ' meters') +
            (hidden.length ? ' · ' + hidden.join(', ') + ' hidden' : '')
        ) +
        '</div>' +
        '</div>';

      if (meters.length === 0) {
        this._metersEl.innerHTML =
          header +
          '<div class="ok-sec ok-note">' +
          esc(
            all.length === 0
              ? 'No Vaadin meters yet. Interact with the application to generate metrics.'
              : 'Every meter is filtered out. Turn a filter off to see them.'
          ) +
          '</div>';
        return;
      }

      var body = groupByRoute(meters)
        .map(function (group) {
          return groupHeading(group) + meterTable(group);
        })
        .join('');

      this._metersEl.innerHTML =
        header + '<div style="padding:0 14px 12px">' + body + '</div>';
    }
  }

  try {
    if (!customElements.get(PANEL_TAG)) {
      customElements.define(PANEL_TAG, ObservabilityMetricsPanel);
    }
  } catch (e) {
    // Defining failed (e.g. unsupported in this context): give up quietly.
    return;
  }

  /**
   * Claims our two commands on Copilot's event bus.
   *
   * Copilot offers every server message to the event bus before it looks for a
   * panel to hand it to, and a panel element only exists while its panel is
   * open - so this is what lets the watch run with the panel closed. The
   * listener must mark the event handled: an unclaimed message is queued for a
   * panel that may never open, and this one arrives every few seconds.
   */
  function listen() {
    var bus = eventBus();
    if (!bus || !bus.on) {
      return;
    }
    [COMMAND_METRICS, COMMAND_INSIGHTS_DATA].forEach(function (command) {
      bus.on(command, function (event) {
        if (accept(command, event.detail)) {
          event.preventDefault();
        }
      });
    });
  }

  var plugin = {
    init: function (copilotInterface) {
      copilot = copilotInterface;
      listen();
      poll();
      setInterval(poll, REFRESH_INTERVAL_MS);
      copilotInterface.addPanel({
        header: 'Observability',
        tag: PANEL_TAG,
        // Plain HTMLElements don't self-position the way Copilot's BasePanel
        // does, and the panel manager skips viewport adjustment when no
        // position is set - so it would open off-screen. Give it an explicit
        // on-screen position and size.
        position: {
          top: 60,
          left: 80,
          width: 540,
          height: 720
        },
        toolbarOptions: {
          iconKey: 'barChart',
          // The toolbar only renders an icon for panels mapped to an active
          // mode; 'common' alone gives no entry point. 'play' hides the panel
          // container, so expose the icon in the remaining modes.
          allowedModesWithOrder: {
            edit: 100,
            inspect: 100,
            test: 100
          }
        }
      });
    }
  };

  // Copilot resets window.Vaadin.copilot.plugins to [] once during bootstrap,
  // so pushing eagerly races that reset and gets wiped. Wait until Copilot has
  // bootstrapped (_uiState is created in the same synchronous block right after
  // the reset) and only then push. At that point either initializePlugins() has
  // already overridden push (so our push inits immediately) or our entry sits in
  // the array until it runs - both register the panel.
  var attempts = 0;
  var maxAttempts = 600; // ~60s at 100ms
  var timer = setInterval(function () {
    attempts++;
    var cp = window.Vaadin && window.Vaadin.copilot;
    if (cp && cp._uiState && Array.isArray(cp.plugins)) {
      clearInterval(timer);
      cp.plugins.push(plugin);
    } else if (attempts >= maxAttempts) {
      clearInterval(timer);
    }
  }, 100);
})();
