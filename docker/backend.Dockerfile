# SPDX-License-Identifier: Apache-2.0
# Backend image. Build the jar first: (cd backend && ./gradlew :ao-app:bootJar)
# (the Gradle build includes ../quasar-dashboard/backend as composite build, so it runs outside Docker).
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 ao
WORKDIR /app
COPY backend/ao-app/build/libs/ao-app-0.1.0-SNAPSHOT.jar /app/app.jar
USER ao
# inside the container the process must bind to 0.0.0.0; compose publishes the port on 127.0.0.1 only (NFR-SEC-06)
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
