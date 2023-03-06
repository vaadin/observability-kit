export MAVEN_OPTS="-javaagent:$1 -Dotel.javaagent.configuration-file=dynatrace.properties"
mvn spring-boot:run -Pproduction
