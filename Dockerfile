FROM node:20-bookworm-slim AS frontend-build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS backend-build
WORKDIR /app

COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml dependency:go-offline

COPY backend backend
COPY --from=frontend-build /app/dist /app/dist

RUN mvn -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=backend-build /app/backend/target/retention-backend-0.1.0-SNAPSHOT.jar app.jar

ENV PORT=8787
EXPOSE 8787

CMD ["java", "-jar", "/app/app.jar"]
