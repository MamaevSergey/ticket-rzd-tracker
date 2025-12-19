# ЭТАП 1: Сборка (Build)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
# Копируем конфиги сборки и скачиваем зависимости (чтобы кэшировалось)
COPY pom.xml .
RUN mvn dependency:go-offline

# Копируем исходный код и собираем jar
COPY src ./src
RUN mvn clean package -DskipTests

# ЭТАП 2: Запуск (Run)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Копируем только скомпилированный jar из первого этапа
COPY --from=build /app/target/*.jar app.jar

# Указываем команду запуска
ENTRYPOINT ["java", "-jar", "app.jar"]