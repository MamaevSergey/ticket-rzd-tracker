# RZD Ticket Tracker

**Telegram-бот для автоматического мониторинга и мгновенного оповещения о свободных местах в плацкартных вагонах**

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.1-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![Telegram Bots](https://img.shields.io/badge/Telegram_Bots_API-6.8.0-blue?logo=telegram)](https://github.com/rubenlagus/TelegramBots)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16--alpine-blue?logo=postgresql)](https://www.postgresql.org/)
[![Liquibase](https://img.shields.io/badge/Liquibase-4.24.0-red?logo=liquibase)](https://www.liquibase.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker)](https://www.docker.com/)

<p align="center">
  <a href="#-о-проекте">О проекте</a> •
  <a href="#-стек-технологий">Стек</a> •
  <a href="#-архитектура-и-надежность">Архитектура</a> •
  <a href="#-возможности">Возможности</a> •
  <a href="#-быстрый-старт">Быстрый старт</a> •
  <a href="#-roadmap-v20">Roadmap</a>
</p>

---

## 📌 О проекте

В сезон отпусков и праздников билеты на популярные железнодорожные направления раскупаются за считанные минуты. Пассажирам приходится часами вручную обновлять страницу сайта в надежде поймать сданный кем-то билет, что отнимает время и отвлекает от работы.

**RZD Ticket Tracker** решает эту проблему, автоматизируя процесс отслеживания:
- Пользователь в 3 клика настраивает маршрут и дату поездки через Telegram-бота.
- Сервис берет на себя периодическую проверку появления свободных нижних полок в плацкарте.
- Как только место появляется, пассажир мгновенно получает push-уведомление с прямой ссылкой на официальное оформление билета.

---

## 🛠 Стек технологий

| Слой | Технологии и версии |
|---|---|
| **Core & Backend** | Java 21, Spring Boot 3.2.1 (Spring Context, Spring Scheduling) |
| **Data & Persistence** | PostgreSQL 16 (Alpine), Spring Data JPA, Hibernate ORM 6.4.1, Liquibase Core 4.24.0 |
| **Network & HTTP** | Java 21 `HttpClient` (HTTP/2), Jackson Databind 2.15.3, Custom SSLContext |
| **Bot Platform** | TelegramBots Spring Boot Starter 6.8.0 (Long Polling, Inline Keyboards, FSM) |
| **DevOps & Infra** | Docker, Docker Compose, Maven 3.9, Project Lombok |

---

## Архитектура и надежность

Проект спроектирован с упором на отказоустойчивость, чистую архитектуру и бережное взаимодействие с внешними сервисами:

- **FSM-архитектура диалогов (State Pattern):** 
  Шаги пользователя (`AWAITING_ORIGIN_INPUT` → `AWAITING_DEST_INPUT` → `AWAITING_DATE` → `AWAITING_TRAIN_SELECTION` → `IDLE`) сохраняются в PostgreSQL. При перезапуске контейнера или обновлении приложения пользователь не теряет контекст ввода.
- **Щадящий сетевой протокол (Rate Limiting & Throttling):**
  Сервис не создает паразитной нагрузки: запросы разнесены во времени фиксированными интервалами, опрос активных задач выполняется последовательно, а при сетевых задержках или ответах `429/503` применяется алгоритм **Exponential Backoff с Jitter** (экспоненциальный откат с рандомизацией пауз).
- **Поддержка национальной PKI (SSL):**
  Реализована кастомная конфигурация `SSLContext` в HTTP-клиенте, обеспечивающая корректную валидацию соединений с отечественными веб-шлюзами, использующими сертификаты Russian Trusted Root CA.
- **Двухуровневая фильтрация мест:**
  Анализатор разбирает дерево вагонов и отсекает нерелевантные типы (купе, СВ, сидячие) и ярусы (верхние, багажные), таргетируясь исключительно на дефицитные нижние полки плацкарта (`Lower` / `SideLower`).
- **Разделение ответственности (Clean Code):**
  Верстка интерфейса Telegram (`KeyboardFactory`), бизнес-логика мониторинга (`TrackingService`), интеграционный HTTP-шлюз (`RzdService`) и хранилище данных изолированы друг от друга.

---

## Возможности

- **Удобный поиск станций:** Поиск по фрагменту названия через Suggest API системы «Экспресс-3» с дедупликацией региональных станций и компактными `callback_data` для инлайн-кнопок.
- **Выбор конкретного рейса:** Бот подтягивает реальное расписание поездов на выбранную дату с указанием времени отправления и прибытия, позволяя выбрать как конкретный поезд, так и опцию «Любой рейс».
- **Мгновенный переход к покупке:** Уведомление содержит номер вагона, номер полки, стоимость и готовую ссылку на официальный веб-интерфейс оформления заказа.
- **Управление задачами:** Команды меню `/new`, `/tasks`, `/cancel` позволяют в любой момент проверить статус или остановить мониторинг в один клик.

---

## Галерея

### Начало взаимодействия с ботом
<img width="553" height="969" alt="1" src="https://github.com/user-attachments/assets/1fa25a68-bf08-4c98-8e8e-879a6ea01dbc" />


### Начало трекинга
<img width="561" height="970" alt="2" src="https://github.com/user-attachments/assets/d3ede3ff-9eab-4bb9-b2d5-3e9d24a1e525" />


### Дополнительный функционал
<img width="560" height="970" alt="5" src="https://github.com/user-attachments/assets/f5e3937a-85a3-4d52-b527-9c9726760574" />

---

## 🚀 Быстрый старт

### Требования
- Установленный [Docker](https://docs.docker.com/get-docker/) и [Docker Compose](https://docs.docker.com/compose/).
- Токен Telegram-бота, полученный у [@BotFather](https://t.me/BotFather).

### Развертывание

1. Клонируйте репозиторий:
   ```bash
   git clone [https://github.com/MamaevSergey/ticket-rzd-tracker.git](https://github.com/MamaevSergey/ticket-rzd-tracker.git)
   cd ticket-rzd-tracker
2. Настройте окружение в postgres и app (environment):
   ```bash
   В Postgres укажите свои:
   POSTGRES_DB: (Название вашей базы данных, например: rzd_tracker)
   POSTGRES_USER: (Имя пользователя базы данных, например: rzd_user)
   POSTGRES_PASSWORD: (Пароль от вашей базы данных, например: rzd_secret_password)
   (Имя пользователя и пароль от базы данных нужно будет указать в environment app)
   ```

   ```bash
   В app укажите свои:
      SPRING_DATASOURCE_USERNAME: (Имя пользователя базы данных)
      SPRING_DATASOURCE_PASSWORD: (Пароль от базы данных)
      BOT_TOKEN: (Токен от бота в телеграмм, который выдаст BotFather)
      BOT_USERNAME: (Название вашего бота в телеграмм, например: rzd_ticker_trackerBot)
   ```

3. Запустите одной командой бота:
  ```bash
  docker compose up -d --build
  ```

4. Если хотите просмотреть логи бота:
   ```bash
   docker compose logs app -f
   ```

## 🗺 Roadmap (v2.0)
- [ ] Мульти-отслеживание: Возможность мониторинга нескольких альтернативных дат и направлений одновременно.
- [ ] Расширенные фильтры: Добавление выбора типа вагона (Купе / СВ / Сидячий) и исключение боковых мест у санузла.
