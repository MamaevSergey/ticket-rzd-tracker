package org.example.ticketrzdtracker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticketrzdtracker.bot.RzdTelegramBot;
import org.example.ticketrzdtracker.model.TaskStatus;
import org.example.ticketrzdtracker.model.TicketResult;
import org.example.ticketrzdtracker.model.TrackingTask;
import org.example.ticketrzdtracker.repository.TrackingTaskRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingService {

    private final TrackingTaskRepository trackingTaskRepository;
    private final RzdService rzdService;
    private final RzdTelegramBot rzdTelegramBot;
    private static final DateTimeFormatter RZD_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void processActiveTrackingTasks() {
        cleanUpExpiredTasks();

        List<TrackingTask> activeTasks = trackingTaskRepository.findAllByStatus(TaskStatus.ACTIVE);
        if (activeTasks.isEmpty()) {
            return;
        }

        log.info("Запуск проверки билетов для {} активных задач", activeTasks.size());

        for (TrackingTask task : activeTasks) {
            try {
                checkTaskForTickets(task);
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Цикл опроса прерван");
                break;
            } catch (Exception e) {
                log.error("Ошибка при обработке задачи ID #{}: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void checkTaskForTickets(TrackingTask task) {
        String formattedDate = task.getDepartureDate().format(RZD_DATE_FORMAT);

        List<TicketResult> availableTickets = rzdService.checkPlatsLowerTickets(
                task.getOriginCode(),
                task.getDestinationCode(),
                formattedDate,
                task.getTrainNumber()
        );

        task.setLastCheckedAt(OffsetDateTime.now());

        if (availableTickets != null && !availableTickets.isEmpty()) {
            log.info("Билеты найдены для задачи ID #{} (chatId: {})", task.getId(), task.getChatId());
            notifyUserAndCompleteTask(task, availableTickets);
        } else {
            trackingTaskRepository.save(task);
        }
    }

    private void notifyUserAndCompleteTask(TrackingTask task, List<TicketResult> tickets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Найдены билеты в плацкарт (нижние полки)\n\n");
        sb.append(String.format("Маршрут: %s → %s\n", task.getOriginStationName(), task.getDestinationStationName()));
        sb.append(String.format("Дата отправления: %s\n", task.getDepartureDate()));
        if (task.getTrainNumber() != null && !task.getTrainNumber().equalsIgnoreCase("ANY")) {
            sb.append(String.format("Поезд: %s\n\n", task.getTrainNumber()));
        } else {
            sb.append("\n");
        }

        sb.append("Доступные места:\n");
        int count = 0;
        for (TicketResult ticket : tickets) {
            if (count++ >= 8) break;
            sb.append(String.format("• Вагон %s, место %d (%s) — %.2f руб.\n",
                    ticket.getCarNumber(),
                    ticket.getSeatNumber(),
                    ticket.getPlaceTypeName(),
                    ticket.getPrice()
            ));
        }

        String rzdWebUrl = String.format(
                "https://ticket.rzd.ru/search/%s/%s/%s",
                task.getOriginCode(),
                task.getDestinationCode(),
                task.getDepartureDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        );

        sb.append(String.format("\nОформить билет на сайте РЖД:\n%s\n\n", rzdWebUrl));
        sb.append("Мониторинг завершен. Для нового поиска используйте команду /new");

        rzdTelegramBot.sendText(task.getChatId(), sb.toString());

        task.setStatus(TaskStatus.TICKET_FOUND);
        trackingTaskRepository.save(task);
    }

    @Transactional
    public void cleanUpExpiredTasks() {
        List<TrackingTask> expiredTasks = trackingTaskRepository.findAllByStatusAndDepartureDateBefore(
                TaskStatus.ACTIVE,
                LocalDate.now()
        );

        if (!expiredTasks.isEmpty()) {
            for (TrackingTask task : expiredTasks) {
                task.setStatus(TaskStatus.EXPIRED);
            }
            trackingTaskRepository.saveAll(expiredTasks);
            log.info("Деактивировано просроченных задач: {}", expiredTasks.size());
        }
    }
}