# Observability Kit demo application

This demo application is a Vaadin application from start.vaadin.com tuned to
test slowness, errors and other issues that are detected by Observability Kit.

## Running the application

The project is a standard Maven project. To run it from the command line,
type `mvn` then open http://localhost:8080 in your browser.

You can also import the project to your IDE of choice as you would with any
Maven project. Read more on [how to import Vaadin projects to different 
IDEs](https://vaadin.com/docs/latest/flow/guide/step-by-step/importing) (Eclipse, IntelliJ IDEA, NetBeans, and VS Code).

Application users are hard-coded in `SecurityConfig` class.
You can login using:

* user/user
* admin/admin

## Start Observability Environment

The project contains a [docker compose](https://docs.docker.com/compose/) configuration to set up a simple observability environment
for collecting traces, metrics and logs from the demo Vaadin application instrumented with the Vaadin Observability Agent.

The `docker-compose.yml` file can be found in `runtime/docker-compose/`.

To start the environment, from the `runtime/docker-compose/` directory, run `docker compose up -d`.

The setup runs the OpenTelemetry Collector which exposes the following endpoints for receiving data:
- `http://localhost:4317` (OTLP GRPC)
- `http://localhost:4318` (OTLP HTTP)

The Grafana UI is available at http://localhost:3000, the credentials for the initial admin user are `admin` / `admin`.
Prometheus is available at http://localhost:9090.
The setup also provides a sample dashboard: http://localhost:3000/d/6_bNYpGVz/vaadin-dashboard?orgId=1.

To stop all services in this setup, run:
```
docker-compose stop
```

To remove all services and volumes from this setup, run:
```
docker-compose down -v
```

### Services

A quick overview of the services run by this setup, and how they interact with each other.

#### OpenTelemetry Collector

The collector is configured to receive trace, metrics and log data in the `OTLP` format either through `GRPC` (port 4317) or `HTTP` (port 4318).

It then distributes that data to individual services that are then used by Grafana to query data from:
- Traces are sent to Grafana Tempo
- Metrics are exposed to be scraped by Prometheus
- Logs are sent to Grafana Loki

#### Grafana Tempo

Receives traces from the OpenTelemetry Collector and stores them.

Exposes a query API that is used by Grafana to search for traces.

#### Prometheus

Scrapes metrics from an endpoint provided by the OpenTelemetry Collector and stores them.

Exposes a query API that is used by Grafana to search for metric.

#### Loki

Receives logs from the OpenTelemetry Collector and stores them.

Exposes a query API that is used by Grafana to search for logs.

#### Grafana

Provides the UI to display the traces, metrics and logs. The Grafana setup automatically provisions data sources for collecting the respective data from Tempo, Prometheus and Loki. It also includes a basic dashboard for showing some metrics, traces and logs - this requires the OpenTelemetry service name to be configured as `vaadin`, which is the default when using the Vaadin Observability agent.

Additional information can be found in `runtime/docker-compose/README.md`.

## Run with Instrumentation

To run the project with the agent configured, you can add the `observe` profile to the maven command.

`mvn spring-boot:run -Pobserve`

or, if you want to observe a production build

`mvn spring-boot:run -Pproduction,observe`

The application is available at http://localhost:8080

## Build and run demo application as docker container  

To build a docker image for the demo application type

`mvn spring-boot:build-image -Pproduction`

The command will produce a docker image for the application tagged as `vaadin/observability-kit-demo:latest`
and `vaadin/observability-kit-demo:X.Y-SNAPSHOT`.

You can then run application along with the other docker compose services, with the command

`docker compose --profile=full up -d`

The application will then be available at http://localhost:8080.

To run the agent inside a container it is necessary to provide a license key.
This can be done either by exporting an environment variable
(e.g. `export VAADIN_OFFLINE_KEY=$(cat /local/path/to/offlineKey)`)
or mounting the license key file into the container `/home/cnb/.vaadin` directory.

Current docker-compose configuration expects the `VAADIN_OFFLINE_KEY` environment variable to be exported.


To mount the license file you can add the following volume to the `demo` service
in the docker compose file:

```
services:
  demo:
    volumes:
      - /local/path/to/offlineKey:/home/cnb/.vaadin/offlineKey

```

To get debug information from the agent export the `OTEL_JAVAAGENT_DEBUG` environment variable
(e.g. `export OTEL_JAVAAGENT_DEBUG=true`).


## Deploying to Production

To create a production build, call `mvnw clean package -Pproduction` (Windows),
or `./mvnw clean package -Pproduction` (Mac & Linux).
This will build a JAR file with all the dependencies and front-end resources,
ready to be deployed. The file can be found in the `target` folder after the build completes.

Once the JAR file is built, you can run it using
`java -jar target/observability-kit-demo-X.Y-SNAPSHOT.jar`

## Project structure

- `MainLayout.java` in `src/main/java` contains the navigation setup (i.e., the
  side/top bar and the main menu). This setup uses
  [App Layout](https://vaadin.com/components/vaadin-app-layout).
- `views` package in `src/main/java` contains the server-side Java views of your application.
- `views` folder in `frontend/` contains the client-side JavaScript views of your application.
- `themes` folder in `frontend/` contains the custom CSS styles.

## Useful links

- Read the documentation at [vaadin.com/docs](https://vaadin.com/docs).
- Follow the tutorials at [vaadin.com/tutorials](https://vaadin.com/tutorials).
- Watch training videos and get certified at [vaadin.com/learn/training](https://vaadin.com/learn/training).
- Create new projects at [start.vaadin.com](https://start.vaadin.com/).
- Search UI components and their usage examples at [vaadin.com/components](https://vaadin.com/components).
- View use case applications that demonstrate Vaadin capabilities at [vaadin.com/examples-and-demos](https://vaadin.com/examples-and-demos).
- Discover Vaadin's set of CSS utility classes that enable building any UI without custom CSS in the [docs](https://vaadin.com/docs/latest/ds/foundation/utility-classes). 
- Find a collection of solutions to common use cases in [Vaadin Cookbook](https://cookbook.vaadin.com/).
- Find Add-ons at [vaadin.com/directory](https://vaadin.com/directory).
- Ask questions on [Stack Overflow](https://stackoverflow.com/questions/tagged/vaadin) or join our [Discord channel](https://discord.gg/MYFq5RTbBn).
- Report issues, create pull requests in [GitHub](https://github.com/vaadin/platform).
