# 빌드 먼저: ./mvnw -pl service-discovery -am package -DskipTests   (target/service-discovery-0.0.1.jar 생성)
FROM eclipse-temurin:21-jre
VOLUME /tmp
COPY target/service-discovery-0.0.1.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
