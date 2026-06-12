// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Dev-mode Vaadin Copilot panel for observability-kit. Injected per UI by
// ObservabilityDevToolsClient via Page.executeJs (development mode only).
// Registers a Copilot plugin that renders the live vaadin.* Micrometer meters
// snapshotted by ObservabilityDevToolsHandler on the server. The IIFE is
// idempotent so repeated injection does not re-register the plugin.
(function () {
  if (window.__vaadinObservabilityDevToolsInstalled) {
    return;
  }
  window.__vaadinObservabilityDevToolsInstalled = true;

  var PANEL_TAG = 'observability-kit-metrics-panel';
  var COMMAND_REFRESH = 'observability-kit-refresh';
  var COMMAND_METRICS = 'observability-kit-metrics';
  var REFRESH_INTERVAL_MS = 3000;

  // CopilotInterface captured at plugin init; used by the panel to talk to the
  // server over the dev-tools websocket.
  var copilot = null;
  // Last snapshot received from the server, shared so a freshly opened panel
  // can render immediately before its first refresh round-trips.
  var latest = null;

  function num(value, decimals) {
    if (typeof value !== 'number' || !isFinite(value)) {
      return String(value);
    }
    if (Number.isInteger(value)) {
      return String(value);
    }
    return value.toFixed(decimals == null ? 1 : decimals);
  }

  function formatTags(tags) {
    var keys = Object.keys(tags || {});
    if (keys.length === 0) {
      return '';
    }
    return keys
      .map(function (k) {
        return k + '=' + tags[k];
      })
      .join(', ');
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

  class ObservabilityMetricsPanel extends HTMLElement {
    connectedCallback() {
      this.style.display = 'block';
      this.style.height = '100%';
      this.style.overflow = 'auto';
      this.render();
      this.requestRefresh();
      this._timer = setInterval(() => this.requestRefresh(), REFRESH_INTERVAL_MS);
    }

    disconnectedCallback() {
      if (this._timer) {
        clearInterval(this._timer);
        this._timer = null;
      }
    }

    requestRefresh() {
      if (copilot) {
        copilot.send(COMMAND_REFRESH, {});
      }
    }

    // Copilot's panel manager calls this on its panel content element (the
    // method is provided by its internal BasePanel). We position the panel
    // explicitly, so there is nothing to recompute - just satisfy the contract.
    requestLayoutUpdate() {
      return Promise.resolve();
    }

    // Called by Copilot for every server message; we claim the metrics command.
    handleMessage(message) {
      if (message && message.command === COMMAND_METRICS) {
        latest = message.data;
        this.render();
        return true;
      }
      return false;
    }

    render() {
    if (!latest || !latest.meters || latest.meters.length === 0) {
      this.innerHTML =
        '<div style="padding:12px;font:13px sans-serif;color:var(--dev-tools-text-color-secondary,#888)">' +
        'No Vaadin meters yet. Interact with the application to generate metrics.' +
        '</div>';
      return;
    }

    var meters = latest.meters.slice().sort(function (a, b) {
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
    });

    var rows = meters
      .map(function (meter) {
        var tagText = formatTags(meter.tags);
        var nameCell =
          meter.name +
          (tagText
            ? '<div style="color:#888;font-size:11px">' + tagText + '</div>'
            : '');
        return (
          '<tr style="border-bottom:1px solid rgba(128,128,128,.15)">' +
          '<td style="padding:5px 8px;vertical-align:top">' +
          nameCell +
          '</td>' +
          '<td style="padding:5px 8px;vertical-align:top;white-space:nowrap;font-variant-numeric:tabular-nums">' +
          formatMeterValue(meter) +
          '</td>' +
          '</tr>'
        );
      })
      .join('');

    var when = latest.timestamp ? new Date(latest.timestamp).toLocaleTimeString() : '';

    this.innerHTML =
      '<div style="padding:8px 12px;font:13px sans-serif">' +
      '<div style="margin-bottom:8px;color:#888">' +
      meters.length +
      ' meter(s) &middot; updated ' +
      when +
      '</div>' +
      '<table style="border-collapse:collapse;width:100%">' +
      '<thead><tr style="text-align:left;color:#888;border-bottom:1px solid rgba(128,128,128,.3)">' +
      '<th style="padding:4px 8px">Meter</th>' +
      '<th style="padding:4px 8px">Value</th>' +
      '</tr></thead>' +
      '<tbody>' +
      rows +
      '</tbody>' +
      '</table>' +
      '</div>';
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

  var plugin = {
    init: function (copilotInterface) {
      copilot = copilotInterface;
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
          width: 540,
          height: 440
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
