# 빌드 먼저: ./mvnw -pl user-service -am package -DskipTests   (target/user-service-0.0.1.jar 생성)
FROM eclipse-temurin:21-jre
VOLUME /tmp
COPY target/user-service-0.0.1.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
