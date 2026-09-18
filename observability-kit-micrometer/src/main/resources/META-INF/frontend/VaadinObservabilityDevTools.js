// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Dev-mode Vaadin Copilot panel for observability-kit. Injected per UI by
// ObservabilityDevToolsClient via Page.executeJs (development mode only).
// Registers a Copilot plugin with two sections: the insights the server built
// from the retained interactions, queries and browser errors, ranked so the
// worst is first; and below them, collapsed, the live vaadin.* Micrometer
// meters grouped by the route they were recorded on. Both come from
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
  // Per-meter ring buffer of recent trend values, keyed by name+tags. Survives
  // panel close/reopen (module scope) so the sparkline keeps its history.
  var history = {};
  var HISTORY_MAX = 20;

  // Which insight rows the developer has opened, keyed by insightKey. At
  // module scope with the history for the same reason: closing the panel to
  // look at the code and reopening it should not collapse what was being read.
  var expanded = {};
  // Whether the meter table is unfolded. Null until the first snapshot decides
  // it: open when there is nothing wrong, folded away when there is, so the
  // panel opens on the findings without hiding the meters from someone who
  // came for them.
  var metricsOpen = null;

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
      if (action === 'toggle-metrics') {
        // Against what is on screen, not against the variable: it starts null,
        // which renders as open, so negating it would leave the section open
        // and swallow the first click - while still counting as a decision and
        // disabling the fold the first payload is supposed to make.
        metricsOpen = metricsOpen === false;
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
          '<div style="font:13px sans-serif">' +
          '<div data-region="insights"></div>' +
          '<div data-region="metrics"></div>' +
          '</div>';
        this._insightsEl = this.querySelector('[data-region="insights"]');
        this._metersEl = this.querySelector('[data-region="metrics"]');
      }
      this.renderInsights();
      this.renderMeters();
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

      // Decided by the first payload that reaches the panel, and never again:
      // once the developer has folded or unfolded the meters, that is theirs.
      if (metricsOpen === null && latestInsights) {
        metricsOpen = live.length === 0;
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
    }

    renderMeters() {
      var meters = ((latest && latest.meters) || []).slice().sort(function (a, b) {
        return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
      });
      var when = latest && latest.timestamp ? clockTime(latest.timestamp) : '';
      var open = metricsOpen !== false;

      var header =
        '<div data-action="toggle-metrics" style="display:flex;align-items:center;gap:6px;' +
        'padding:8px 12px;cursor:pointer;border-top:1px solid rgba(128,128,128,.25);' +
        'color:#888">' +
        '<span style="width:12px;text-align:center">' +
        (open ? '▾' : '▸') +
        '</span>' +
        '<span>Metrics</span>' +
        '<span style="margin-left:auto">' +
        esc(
          meters.length +
            (meters.length === 1 ? ' meter' : ' meters') +
            (when ? ' · updated ' + when : '')
        ) +
        '</span>' +
        '</div>';

      if (!open) {
        this._metersEl.innerHTML = header;
        return;
      }

      if (meters.length === 0) {
        this._metersEl.innerHTML =
          header +
          '<div style="padding:0 12px 12px;color:var(--dev-tools-text-color-secondary,#888)">' +
          'No Vaadin meters yet. Interact with the application to generate metrics.' +
          '</div>';
        return;
      }

      var body = groupByRoute(meters)
        .map(function (group) {
          return groupHeading(group) + meterTable(group);
        })
        .join('');

      this._metersEl.innerHTML =
        header + '<div style="padding:0 12px 12px">' + body + '</div>';
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
          top: 80,
          left: 80,
          width: 720,
          height: 460
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
