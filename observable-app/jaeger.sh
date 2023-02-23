mvn clean package -Pproduction
java -javaagent:"$1" -Dotel.javaagent.configuration-file=jaeger.properties -jar target/observe-1.0-SNAPSHOT.jar
