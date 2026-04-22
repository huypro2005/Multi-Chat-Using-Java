FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -g 1000 chatapp && adduser -u 1000 -G chatapp -s /bin/sh -D chatapp

COPY --from=builder /app/target/*.jar app.jar
RUN chown chatapp:chatapp app.jar

RUN mkdir -p /app/uploads/default && chown -R chatapp:chatapp /app/uploads

USER chatapp
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
