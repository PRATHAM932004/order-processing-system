# ============================================================
# Stage 1: Build the Spring Boot JAR
# ============================================================
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper and pom.xml first to cache dependencies
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code and build executable JAR
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ============================================================
# Stage 2: Lightweight JRE Runtime for Google Cloud Run
# ============================================================
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /app/target/*.jar app.jar

# Google Cloud Run sets PORT=8080 by default
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
