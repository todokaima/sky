# Build the jar
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /src/target/netcanvas-*.jar app.jar
# APIDOCS_HOST: what "localhost" means in an api-docs URL typed on the page; in a container, the host
ENV PORT=29994 APIDOCS_HOST=host.docker.internal
EXPOSE 29994
ENTRYPOINT ["java", "-jar", "app.jar"]
