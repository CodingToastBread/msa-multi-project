# 빌드 먼저: ./mvnw -pl config-service -am package -DskipTests   (target/config-service-0.0.1.jar 생성)
# [주의] 프로젝트가 Java 21 로 컴파일되므로 17 이미지를 쓰면 UnsupportedClassVersionError 로 바로 죽는다.
FROM eclipse-temurin:21-jre
VOLUME /tmp
# 암호화 키. bootstrap.yml 의 encrypt.key-store.location 을 ENCRYPT_KEY_STORE_LOCATION=file:/apiEncryptionKey.jks 로 덮어써서 사용
COPY apiEncryptionKey.jks apiEncryptionKey.jks
COPY target/config-service-0.0.1.jar ConfigServer.jar
ENTRYPOINT ["java", "-jar", "ConfigServer.jar"]
