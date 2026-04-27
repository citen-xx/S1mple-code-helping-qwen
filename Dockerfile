FROM node:20-alpine AS frontend-builder

WORKDIR /frontend

COPY frontend/package*.json ./
RUN npm ci

COPY frontend ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS backend-builder

WORKDIR /build

COPY pom.xml ./
COPY src ./src
COPY sql ./sql
COPY frontend ./frontend
COPY --from=frontend-builder /frontend/dist ./frontend/dist

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ \
    && rm -rf /var/lib/apt/lists/*

COPY --from=backend-builder /build/target/simple-ai-oj-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
