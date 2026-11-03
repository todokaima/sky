# Build the jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run it; the docker CLI is for the python / psql one-shot containers,
# which start next to this one through the host's docker socket
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache docker-cli
WORKDIR /app
COPY --from=build /src/target/client-*.jar app.jar
ENV PORT=29993 DATA_DIR=/data
VOLUME /data
EXPOSE 29993
ENTRYPOINT ["java", "-jar", "app.jar"]
