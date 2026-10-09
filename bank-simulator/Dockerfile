# Builds inside Docker so only Docker is needed locally. Build context = repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -pl bank-simulator -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/bank-simulator/target/bank-simulator.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
