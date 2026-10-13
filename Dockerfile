# --- Сборка ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# maven-toolchains-plugin требует toolchains.xml; в образе JDK уже temurin-21,
# поэтому пробрасываем путь к нему, чтобы локальная и контейнерная сборки совпадали.
RUN mkdir -p /root/.m2 \
    && printf '<?xml version="1.0" encoding="UTF-8"?>\n\
<toolchains>\n\
  <toolchain>\n\
    <type>jdk</type>\n\
    <provides>\n\
      <version>21</version>\n\
      <vendor>temurin</vendor>\n\
    </provides>\n\
    <configuration>\n\
      <jdkHome>%s</jdkHome>\n\
    </configuration>\n\
  </toolchain>\n\
</toolchains>\n' "$JAVA_HOME" > /root/.m2/toolchains.xml

# Слоистая кэш-сборка: слой зависимостей не пересоздаётся при правках src/
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline

COPY src src
# Тесты гоняются вне образа: интеграционным нужен Docker/Podman (Testcontainers).
# maven.test.skip=true — не компилируем тесты при сборке образа.
RUN ./mvnw -q package -Dmaven.test.skip=true

# --- Запуск ---
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080
# Ограничиваем кучу JVM долей от лимитов контейнера (иначе JVM может упереться в cgroup-лимит).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
