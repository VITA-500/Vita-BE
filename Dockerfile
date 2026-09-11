FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --version

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# 04_API명세서 0절 확정: 모든 timestamp는 KST(Asia/Seoul) 고정. LocalDateTime.now()가 JVM 기본
# 타임존을 따라가므로 컨테이너 기본값(UTC)이 아니라 여기서 명시적으로 고정해야 한다.
ENV TZ=Asia/Seoul

EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
