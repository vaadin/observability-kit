# Vaadin Observability Grafana Setup

This repo provides a docker-compose setup for collecting traces, metrics and logs from a Vaadin application instrumented with the Vaadin Observability Agent into Grafana.  

> **Warning**
> This setup is only intended as a local test setup. It is not production ready.

To start the setup, just run:
```
docker-compose up
```

The setup runs the OpenTelemetry Collector which exposes the following endpoints for receiving data:
- `http://localhost:4317` (OTLP GRPC)
- `http://localhost:4318` (OTLP HTTP)

To configure the agent to send data to this setup you can use the provided `agent.properties` file, which contains the following contents:
```
otel.service.name=vaadin
otel.traces.exporter=otlp
otel.metrics.exporter=otlp
otel.logs.exporter=otlp
```

Then, from the demo project folder, start your Vaadin app together with the agent, for example:
```
java -javaagent:../observability-kit-agent/target/observability-kit-agent-X.Y-SNAPSHOT.jar \
     -Dotel.javaagent.configuration-file=runtime/docker-compose/agent.properties \
     -jar target/observability-kit-demo-X.Y-SNAPSHOT.jar
```

The Grafana UI is available at http://localhost:3000, the credentials for the initial admin user are `admin` / `admin`.
The setup also provides a sample dashboard: http://localhost:3000/d/6_bNYpGVz/vaadin-dashboard?orgId=1.

To stop all services in this setup, run:
```
docker-compose stop
```

To remove all services and volumes from this setup, run:
```
docker-compose down -v
```

## Services

A quick overview of the services run by this setup, and how they interact with each other.

### OpenTelemetry Collector

The collector is configured to receive trace, metrics and log data in the `OTLP` format either through `GRPC` (port 4317) or `HTTP` (port 4318).

It then distributes that data to individual services that are then used by Grafana to query data from:
- Traces are sent to Grafana Tempo
- Metrics are exposed to be scraped by Prometheus
- Logs are sent to Grafana Loki

### Grafana Tempo

Receives traces from the OpenTelemetry Collector and stores them.

Exposes a query API that is used by Grafana to search for traces.

### Prometheus

Scrapes metrics from an endpoint provided by the OpenTelemetry Collector and stores them.

Exposes a query API that is used by Grafana to search for metric.

### Loki

Receives logs from the OpenTelemetry Collector and stores them.

Exposes a query API that is used by Grafana to search for logs.

### Grafana

Provides the UI to display the traces, metrics and logs. The Grafana setup automatically provisions data sources for collecting the respective data from Tempo, Prometheus and Loki. It also includes a basic dashboard for showing some metrics, traces and logs - this requires the OpenTelemetry service name to be configured as `vaadin`, which is the default when using the Vaadin Observability agent.
