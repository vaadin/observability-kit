# Vaadin observability - OpenTelemetry Extension

Test project for extending the OpenTelemetry with better data

To build the project build an embedded extension with:

`./gradlew clean build extendedAgent`

Sample usage would then be for instance:

`java -javaagent:../extension/build/libs/opentelemetry-javaagent.jar -Dotel.traces.exporter=jaeger -Dotel.exporter.jaeger.endpoint=http://localhost:14250 -Dotel.metrics.exporter=none -jar target/myapp-1.0-SNAPSHOT.jar`

 The automatic vaadin instrumentation package is disabled so we can handle all the things in a custom way.

Using the project as an extension (instead of embedding):
`java -javaagent:../opentelemetry-javaagent.jar -Dotel.traces.exporter=jaeger -Dotel.javaagent.extensions=../extension/build/libs/opentelemetry-custom-instrumentation-extension-1.0-all.jar -Dotel.exporter.jaeger.endpoint=http://localhost:14250 -Dotel.metrics.exporter=none -jar target/myapp-1.0-SNAPSHOT.jar`

This has the paths  
`/extension` (this project)
`/my-app` (the application)

Also running Jaeger `jaeger-all-in-one.exe` with default settings.

## Development

### Linting and Formatting

The project uses [spotless][https://github.com/diffplug/spotless/tree/main/plugin-gradle] for linting and formatting the code.

To lint the code run:
```
./gradlew spotlessCheck
```

To format the code run:
```
./gradlew spotlessApply
```
