FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY pom.xml ./
# Resolve project dependencies in a cacheable layer. `dependency:go-offline`
# also downloads every reporting/site plugin from the parent POM and makes a
# clean Day 7 build unnecessarily slow.
RUN mvn -B -Dmaven.wagon.http.retryHandler.count=5 -DskipTests dependency:resolve
COPY src ./src
RUN mvn -B -Dmaven.wagon.http.retryHandler.count=5 -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home autocve
COPY --from=builder /workspace/target/java-agent-service-*.jar /app/java-agent-service.jar
USER autocve

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/java-agent-service.jar"]
