# Vaadin Observability Kit

Production telemetry for [Vaadin Flow](https://vaadin.com) applications, using
[Micrometer](https://micrometer.io). The kit instruments the Vaadin runtime —
sessions, UIs, navigation, requests, errors and real browser-side timing — and
records everything into your application's `MeterRegistry`, so it shows up in
whatever backend you already use (Prometheus, OTLP, Graphite, …). Tracing spans
are emitted through the Micrometer Observation API.

It is a drop-in: with Spring Boot you **add one dependency and you're done** — no
code, no annotations, no configuration required.

> Observability Kit is a commercial Vaadin product. See [License](#license).

## Requirements

- Java 21 or newer
- Vaadin 25.3 or newer (Flow 25.3+)
- A Micrometer `MeterRegistry` — the Spring Boot starter provides one out of the box
- Spring Boot 4 (only for the `observability-kit-starter`; plain-Spring and
  standalone setups are also supported)

## Getting started (Spring Boot)

Add the starter:

```xml
<dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>observability-kit-starter</artifactId>
    <version>5.0-SNAPSHOT</version>
</dependency>
```

That's the whole setup. On startup the kit auto-configures a `MeterRegistry`
(through Spring Boot's Micrometer support) and wires the Vaadin instrumentation
onto it. Sessions, UIs, navigation, request handling, errors and client-side
timing all start recording automatically.

### Exposing the metrics

The kit *records* into a registry; to *export* the metrics, add Spring Boot
Actuator and the registry backend of your choice — for example Prometheus:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=prometheus
```

The metrics are then available at `GET /actuator/prometheus`.

## Working with the metrics in your application

Everything the kit records lives in the application `MeterRegistry`. You can read
it from anywhere a bean is injectable — including a Vaadin view — and record your
own meters right alongside the built-in ones:

```java
@Route("latency")
public class LatencyView extends VerticalLayout {

    private static final String SERVER_TIMER = "vaadin.request.duration";

    private final transient MeterRegistry registry;

    public LatencyView(MeterRegistry registry) {
        this.registry = registry;

        add(new Button("Do work", e -> timed("do-work", () -> doWork())));
        add(new Button("Show server timing", e -> {
            Timer timer = registry.find(SERVER_TIMER).timer();
            if (timer != null) {
                Notification.show("%d requests, max %.0f ms".formatted(
                        timer.count(), timer.max(TimeUnit.MILLISECONDS)));
            }
        }));
    }

    /** Record a custom timer next to the kit's built-in meters. */
    private void timed(String action, Runnable work) {
        Timer.Sample sample = Timer.start(registry);
        try {
            work.run();
        } finally {
            sample.stop(registry.timer("app.interaction", "action", action));
        }
    }
}
```

The built-in server-side request timer is `vaadin.request.duration`; the
browser-observed round trip (when client metrics are enabled) is
`vaadin.client.rpc.duration`. See [Metrics](#metrics) for the full list.

## Other setups

### Plain Spring (without Spring Boot)

Add the Spring module, import the configuration, and provide a `MeterRegistry`
bean:

```xml
<dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>observability-kit-spring</artifactId>
    <version>5.0-SNAPSHOT</version>
</dependency>
```

```java
@Configuration
@Import(ObservabilityConfiguration.class)
class ObservabilityConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
```

### Standalone (without Spring)

Add the core module and install the kit at servlet-context startup — for example
from a `ServletContextListener` — so the registry is in place before the
`VaadinService` initializes:

```xml
<dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>observability-kit-micrometer</artifactId>
    <version>5.0-SNAPSHOT</version>
</dependency>
```

```java
@WebListener
public class ObservabilitySetup implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        MeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityKit.install(registry,
                ObservabilitySettings.builder().build());
    }
}
```

## Configuration

Every feature is enabled by default. With the Spring Boot starter, configure the
kit through `vaadin.observability.*` properties:

```properties
# Turn the whole kit off
vaadin.observability.enabled=false

# Or toggle individual feature groups
vaadin.observability.client=false
vaadin.observability.traces=false
```

| Property | Default | Description |
| --- | --- | --- |
| `vaadin.observability.enabled` | `true` | Master switch for the auto-configuration. |
| `vaadin.observability.sessions` | `true` | Session count, lifetime and lock metrics. |
| `vaadin.observability.uis` | `true` | UI count metrics. |
| `vaadin.observability.navigation` | `true` | Navigation timing. |
| `vaadin.observability.requests` | `true` | Server-side request and RPC timing. |
| `vaadin.observability.errors` | `true` | Error counters. |
| `vaadin.observability.client` | `true` | Browser-side timing collected from the client. |
| `vaadin.observability.database` | `false` | Wrap `DataSource` beans to record JDBC result-set sizes per route (Spring Boot starter only). |
| `vaadin.observability.traces` | `true` | Emit tracing spans via the Observation API. |
| `vaadin.observability.traces-session-id` | `false` | Include the session id as a span attribute. |
| `vaadin.observability.route-cardinality-limit` | `200` | Maximum number of distinct `route` tag values before they collapse to `_other`. |
| `vaadin.observability.client-rate-per-session` | `100` | Maximum client-side samples accepted per session (throttling guard). |

For plain Spring the same keys are read via `@Value`; for standalone use, build an
`ObservabilitySettings` with the matching builder methods:

```java
ObservabilitySettings.builder()
        .client(false)
        .traces(false)
        .routeCardinalityLimit(500)
        .build();
```

## Metrics

| Meter | Type | Description |
| --- | --- | --- |
| `vaadin.sessions.active` | Gauge | Currently active sessions. |
| `vaadin.sessions.created` | Counter | Sessions created. |
| `vaadin.sessions.duration` | Timer | Session lifetime. |
| `vaadin.session.lock.wait` | Timer | Time spent waiting to acquire the session lock. |
| `vaadin.session.lock.hold` | Timer | Time the session lock is held. |
| `vaadin.ui.active` | Gauge | Currently active UIs. |
| `vaadin.ui.created` | Counter | UIs created. |
| `vaadin.navigation` | Timer | Navigation duration (tagged by `route`, `outcome`). |
| `vaadin.request.duration` | Timer | Server-side request handling time. |
| `vaadin.rpc.duration` | Timer | Server-side RPC invocation time (tagged by `type`). |
| `vaadin.errors` | Counter | Server-side errors (tagged by `exception`). |
| `vaadin.client.bootstrap.duration` | Timer | Browser application bootstrap time. |
| `vaadin.client.navigation.duration` | Timer | Browser-observed navigation time. |
| `vaadin.client.rpc.duration` | Timer | Browser-observed server round trip. |
| `vaadin.client.web_vitals.lcp` | Timer | Largest Contentful Paint. |
| `vaadin.client.web_vitals.fcp` | Timer | First Contentful Paint. |
| `vaadin.client.errors` | Counter | Errors reported by the browser. |
| `vaadin.client.dropped` | Counter | Client samples dropped before recording. |
| `vaadin.client.throttled` | Counter | Client samples rejected by the per-session rate limit. |
| `vaadin.db.fetch.rows` | DistributionSummary | Rows read from a JDBC result set, tagged by `route` (opt-in, see `vaadin.observability.database`). |

## Database fetch size

With the Spring Boot starter you can have the kit watch how many rows your
queries return, without touching application code. Enable it with:

```properties
vaadin.observability.database=true
```

Every `DataSource` bean is then wrapped so each JDBC `ResultSet` reports its row
count into the `vaadin.db.fetch.rows` distribution summary, **tagged by the
Vaadin `route`** that triggered the fetch — so you can see which view issues the
large reads. Watch the p95/p99 of that summary, and alert on it in your metrics
backend (for example a Prometheus rule on `vaadin_db_fetch_rows`) to catch
runaway result sets in production.

This is off by default: it reaches outside the Vaadin runtime into the
persistence layer and adds a small per-row cost. It covers all JDBC access
(Spring Data, `JdbcTemplate`, raw JDBC) that flows through a managed
`DataSource`; row counting is best-effort and attributes to `_unknown` when no
view is active (for example background tasks).

## Tracing

When tracing is enabled (the default) and an `ObservationRegistry` is available,
the kit emits spans through the Micrometer Observation API for the Vaadin request
lifecycle, navigation and RPC. Spring Boot Actuator supplies an
`ObservationRegistry` automatically; the standalone bootstrap creates one for you.
To export the spans, add a Micrometer tracing bridge (for example OpenTelemetry or
Zipkin) as you would for any Micrometer-instrumented application. Set
`vaadin.observability.traces=false` to disable span emission.

## License

Observability Kit is available under the Vaadin Commercial License and Service
Terms. See <https://vaadin.com/commercial-license-and-service-terms>.
