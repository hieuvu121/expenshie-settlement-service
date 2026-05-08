FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

COPY common/pom.xml ./common/pom.xml
COPY common/src ./common/src
RUN mvn -f common/pom.xml install -DskipTests -B

COPY settlement-service/pom.xml ./settlement-service/pom.xml
RUN mvn -f settlement-service/pom.xml dependency:go-offline -B

COPY settlement-service/src ./settlement-service/src
RUN mvn -f settlement-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/settlement-service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
