# 1단계: 빌드 (JDK 포함, 이미지가 큼)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 의존성 정의만 먼저 복사 → 소스만 바뀌면 의존성 다운로드 레이어는 캐시 재사용
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon -q > /dev/null
COPY src src
# 테스트는 CI의 backend job에서 이미 통과했으므로 여기선 건너뜀
RUN ./gradlew bootJar --no-daemon -x test

# 2단계: 실행 (JRE만, 빌드 도구·소스 없음)
FROM eclipse-temurin:21-jre
WORKDIR /app
# root가 아닌 사용자로 실행 (컨테이너 탈출 시 피해 축소)
RUN useradd --system --uid 1001 app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8090
# EC2 메모리 1GB에 MySQL과 같이 올리므로 힙 상한을 둔다. SerialGC는 작은 힙에서 메모리 오버헤드가 가장 적음
ENV JAVA_OPTS="-Xms128m -Xmx320m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
