# Vaadin Observability Kit - OpenTelemetry Extension

Extensions to the OpenTelemetry Java agent with Vaadin metrics and traces.

To build the agent with the embedded extension, run:

`mvn package`

Sample usage would then be for instance:

`java -javaagent:/path/to/observability-kit-agent.jar -Dotel.traces.exporter=jaeger -Dotel.exporter.jaeger.endpoint=http://localhost:14250 -Dotel.metrics.exporter=none -jar target/myapp-1.0-SNAPSHOT.jar`

 The automatic Vaadin instrumentation package is disabled so we can handle all the things in a custom way.

Using the project as an extension (instead of embedding):
`java -javaagent:../opentelemetry-javaagent.jar -Dotel.traces.exporter=jaeger -Dotel.javaagent.extensions=/path/to/opentelemetry-vaadin-observability-instrumentation-extension.jar -Dotel.exporter.jaeger.endpoint=http://localhost:14250 -Dotel.metrics.exporter=none -jar target/myapp-1.0-SNAPSHOT.jar`

This has the paths
`/extension` (this project)
`/my-app` (the application)

Also running Jaeger `jaeger-all-in-one.exe` with default settings.

## Development

### Running TomCat with the agent

Tomcat on windows add the file `{tomcat}\bin\setenv.bat` with the content

```shell
set  CATALINA_OPTS=%CATALINA_OPTS% -javaagent:C:\PATH_TO\opentelemetry-javaagent.jar
set  OTEL_METRICS_EXPORTER=none
set  OTEL_TRACES_EXPORTER=jaeger
set  OTEL.EXPORTER.JAEGER.ENDPOINT=http://localhost:14250
```

for linux/unix the file is `{tomcat}/bin/setenv.sh` with the content
```shell
export CATALINA_OPTS="$CATALINA_OPTS -javaagent:/PATH_TO/opentelemetry-javaagent.jar"
export OTEL_METRICS_EXPORTER=none
export OTEL_TRACES_EXPORTER=jaeger
export OTEL.EXPORTER.JAEGER.ENDPOINT=http://localhost:14250
```
