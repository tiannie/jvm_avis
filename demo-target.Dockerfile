# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

COPY pom.xml .
COPY collector/pom.xml collector/pom.xml
COPY demo-target/pom.xml demo-target/pom.xml
COPY demo-target/src demo-target/src

RUN mvn -q -pl demo-target -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /src/demo-target/target/demo-target-0.1.0-SNAPSHOT.jar /app/app.jar

ENV DEMO_HTTP_PORT=8081 \
    JMX_PORT=9010 \
    JAVA_RMI_SERVER_HOSTNAME=demo-target

EXPOSE 8081 9010

# Always-on bounded JFR + JMX (no auth; demo only). RMI hostname must be
# reachable from the collector container (Compose service name by default).
ENTRYPOINT ["sh", "-c", "exec java \
  -XX:StartFlightRecording=name=jvm-avis,settings=default,maxage=5m,maxsize=64m,disk=true,dumponexit=true \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=${JMX_PORT} \
  -Dcom.sun.management.jmxremote.rmi.port=${JMX_PORT} \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Djava.rmi.server.hostname=${JAVA_RMI_SERVER_HOSTNAME} \
  -jar /app/app.jar"]
