FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/discord-stock-market.jar app.jar
COPY config.properties .
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
