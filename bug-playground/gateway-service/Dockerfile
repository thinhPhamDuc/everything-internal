FROM eclipse-temurin:17-jre
WORKDIR /app
# Build JAR ở host TRƯỚC (./gradlew :gateway-service:bootJar) rồi mới
# `docker compose up --build` - tránh phải tải Gradle distro/dependency bên
# trong image build (nhanh + không phụ thuộc mạng lúc build image).
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
