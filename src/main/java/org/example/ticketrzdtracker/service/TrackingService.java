package org.example.ticketrzdtracker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticketrzdtracker.bot.RzdTelegramBot;
import org.example.ticketrzdtracker.model.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class TrackingService { // Убрали @RequiredArgsConstructor
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();

    private final RzdService rzdService;
    private final RzdTelegramBot bot;

    public TrackingService(RzdService rzdService, @Lazy RzdTelegramBot bot) {
        this.rzdService = rzdService;
        this.bot = bot;
    }

    public UserSession getSession(Long chatId) {
        return sessions.computeIfAbsent(chatId, k -> {
            UserSession s = new UserSession();
            s.setChatId(chatId);
            return s;
        });
    }

    public boolean startTracking(Long chatId, String from, String to, String dateStr, int durationSec) {
        log.info("Старт трекинга: {} -> {} на {}", from, to, dateStr);
        UserSession session = getSession(chatId);

        // 1. Ищем коды станций (Новая логика RzdService)
        String fromCode = rzdService.findStationCode(from);
        String toCode = rzdService.findStationCode(to);

        if (fromCode == null || toCode == null) {
            bot.sendMessage(chatId, "❌ Не удалось найти станции. Попробуйте точное название.");
            return false;
        }

        // 2. Ищем поезд и получаем TrainSessionData
        TrainSessionData trainData = rzdService.searchFirstTrain(fromCode, toCode, dateStr);

        if (trainData == null) {
            bot.sendMessage(chatId, "❌ Поездов не найдено.");
            return false;
        }

        // 3. Сохраняем и запускаем
        session.setTrainData(trainData); // Сохранили все данные для запросов
        session.setTrackingEndTime(System.currentTimeMillis() + (durationSec * 1000L));
        session.setState(UserState.TRACKING);

        bot.sendMessage(chatId, String.format("✅ Трекинг запущен!\nПоезд: %s\nВремя: %s",
                trainData.getTrainNumber(), trainData.getDepartureDate()));

        scheduleNextCheck(chatId, session, 0);
        return true;
    }

    private void scheduleNextCheck(Long chatId, UserSession session, long delaySeconds) {
        // Если пользователь остановил бота, выходим из цикла
        if (session.getState() != UserState.TRACKING) {
            return;
        }

        ScheduledFuture<?> task = scheduler.schedule(() -> {
            try {
                // Проверка времени окончания
                if (System.currentTimeMillis() > session.getTrackingEndTime()) {
                    stopTracking(chatId);
                    bot.sendMessage(chatId, "🛑 Время вышло.");
                    return;
                }

                // ЗАПРОС К СЕРВИСУ
                List<TicketResult> seats = rzdService.getCarPricing(session.getTrainData());

                if (!seats.isEmpty()) {
                    StringBuilder msg = new StringBuilder("🔥 НАЙДЕНЫ НИЖНИЕ МЕСТА (ПЛАЦ)!\n");
                    for (TicketResult seat : seats) {
                        msg.append(String.format("Вагон %s | Место %d | %.0f руб.\n",
                                seat.getCarNumber(), seat.getSeatNumber(), seat.getPrice()));
                    }
                    msg.append("\nСсылка: https://ticket.rzd.ru/");
                    bot.sendMessage(chatId, msg.toString());
                } else {
                    log.info("Пусто для {}", chatId);
                }

                long baseDelay = 420;
                long jitter = ThreadLocalRandom.current().nextLong(-30, 31);
                long nextDelay = baseDelay + jitter;

                // Рекурсивный вызов себя же через новое время
                scheduleNextCheck(chatId, session, nextDelay);

            } catch (Exception e) {
                log.error("Ошибка в задаче", e);
                // Если ошибка, пробуем снова через 1 минуту
                scheduleNextCheck(chatId, session, 60);
            }
        }, delaySeconds, TimeUnit.SECONDS);

        session.setTrackingTask(task);
    }

    public void stopTracking(Long chatId) {
        UserSession session = sessions.get(chatId);
        if (session != null && session.getTrackingTask() != null) {
            session.getTrackingTask().cancel(false);
            session.setState(UserState.AUTHENTICATED);
        }
    }
}
