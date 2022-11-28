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

### Maven deployment of extendedAgent

To deploy the extended agent to a maven repository you need to supply credentials as properties `mavenUser` and `mavenPwd`, also the repository URL needs to be given as `mavenUrl`.

These can be given on the command line with the `-P` flag or in the `gradle.properties` file.

If targeting a repository behind http then you need to add the `allowInsecureProtocol = true` flag to the `repositories { maven {` block.

With the parameters in place just run
```shell
gradle build extendedAgent publish
```
