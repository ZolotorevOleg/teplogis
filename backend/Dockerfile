FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:11-jre
RUN groupadd --system app && useradd --system --gid app app && mkdir -p /data/uploads /data/tmp && chown -R app:app /data
WORKDIR /app
COPY --from=build /build/target/heat-routing-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Xms128m", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

