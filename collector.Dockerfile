# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

COPY pom.xml .
COPY collector/pom.xml collector/pom.xml
COPY demo-target/pom.xml demo-target/pom.xml
COPY collector/src collector/src

RUN mvn -q -pl collector -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /src/collector/target/collector-0.1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
