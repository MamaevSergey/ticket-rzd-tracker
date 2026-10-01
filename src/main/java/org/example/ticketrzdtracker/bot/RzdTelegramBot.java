package org.example.ticketrzdtracker.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticketrzdtracker.model.TaskStatus;
import org.example.ticketrzdtracker.model.TrackingTask;
import org.example.ticketrzdtracker.model.User;
import org.example.ticketrzdtracker.model.dto.StationSuggestion;
import org.example.ticketrzdtracker.model.dto.TrainOption;
import org.example.ticketrzdtracker.repository.TrackingTaskRepository;
import org.example.ticketrzdtracker.repository.UserRepository;
import org.example.ticketrzdtracker.service.RzdService;
import org.example.ticketrzdtracker.service.StationSuggesterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RzdTelegramBot extends TelegramLongPollingBot {

    private final StationSuggesterService stationSuggesterService;
    private final TrackingTaskRepository trackingTaskRepository;
    private final UserRepository userRepository;
    private final RzdService rzdService;

    private final Map<Long, TrackingTask> taskDrafts = new ConcurrentHashMap<>();

    @Value("${bot.name}")
    private String botUsername;

    @Value("${bot.token}")
    private String botToken;

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            handleTextMessage(update);
        }
    }

    private void handleTextMessage(Update update) {
        String text = update.getMessage().getText().trim();
        long chatId = update.getMessage().getChatId();
        String username = update.getMessage().getFrom().getUserName();

        User user = userRepository.findById(chatId).orElseGet(() ->
                userRepository.save(new User(chatId, username))
        );

        if ("/cancel".equalsIgnoreCase(text)) {
            handleCancelCommand(chatId, user);
            return;
        }

        if ("/tasks".equalsIgnoreCase(text)) {
            showActiveTasks(chatId);
            return;
        }

        if ("/start".equalsIgnoreCase(text)) {
            sendText(chatId, "Бот отслеживает появление нижних полок в плацкарте на поезда РЖД.\n\n" +
                    "Введите станцию отправления (например, Москва):");
            user.setBotState("AWAITING_ORIGIN_INPUT");
            userRepository.save(user);
            taskDrafts.put(chatId, new TrackingTask());
            return;
        }

        if ("/new".equalsIgnoreCase(text)) {
            startNewSearch(chatId, user);
            return;
        }

        switch (user.getBotState()) {
            case "AWAITING_ORIGIN_INPUT" -> handleStationSearch(chatId, text, "ORIGIN", "отправления");
            case "AWAITING_DEST_INPUT" -> handleStationSearch(chatId, text, "DEST", "прибытия");
            case "AWAITING_DATE" -> handleDateInput(chatId, text, user);
            default -> {
                user.setBotState("AWAITING_ORIGIN_INPUT");
                userRepository.save(user);
                taskDrafts.put(chatId, new TrackingTask());
                handleStationSearch(chatId, text, "ORIGIN", "отправления");
            }
        }
    }

    private void startNewSearch(long chatId, User user) {
        List<TrackingTask> activeTasks = trackingTaskRepository.findAllByChatIdAndStatus(chatId, TaskStatus.ACTIVE);
        if (!activeTasks.isEmpty()) {
            TrackingTask active = activeTasks.get(0);
            sendText(chatId, String.format(
                    "У вас уже отслеживается маршрут %s → %s (%s).\n" +
                            "Чтобы задать новый, сначала отмените текущий через /cancel или /tasks.",
                    active.getOriginStationName(),
                    active.getDestinationStationName(),
                    active.getDepartureDate()
            ));
            return;
        }

        user.setBotState("AWAITING_ORIGIN_INPUT");
        userRepository.save(user);
        taskDrafts.put(chatId, new TrackingTask());
        sendText(chatId, "Введите станцию отправления (например, Екатеринбург):");
    }

    private void handleCancelCommand(long chatId, User user) {
        taskDrafts.remove(chatId);
        user.setBotState("IDLE");
        userRepository.save(user);

        List<TrackingTask> activeTasks = trackingTaskRepository.findAllByChatIdAndStatus(chatId, TaskStatus.ACTIVE);
        if (activeTasks.isEmpty()) {
            sendText(chatId, "Активных отслеживаний не найдено.\nДля создания нового поиска отправьте команду /new");
            return;
        }

        activeTasks.forEach(task -> task.setStatus(TaskStatus.CANCELLED));
        trackingTaskRepository.saveAll(activeTasks);
        sendText(chatId, "Отслеживание отменено.\nТеперь вы можете запустить новый поиск командой /new");
    }

    private void handleStationSearch(long chatId, String query, String prefix, String directionLabel) {
        List<StationSuggestion> suggestions = stationSuggesterService.searchStations(query);
        if (suggestions.isEmpty()) {
            sendText(chatId, "Станция не найдена. Попробуйте уточнить название:");
            return;
        }

        InlineKeyboardMarkup keyboard = KeyboardFactory.createStationPicker(suggestions, prefix);
        if (keyboard == null) {
            sendText(chatId, "Не удалось определить точную станцию. Попробуйте ввести другое название:");
            return;
        }

        SendMessage message = new SendMessage(String.valueOf(chatId), "Выберите точную станцию " + directionLabel + ":");
        message.setReplyMarkup(keyboard);
        executeSafely(message);
    }

    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

        User user = userRepository.findById(chatId).orElse(null);

        if (callbackData.startsWith("CANCEL:")) {
            try {
                Long taskId = Long.parseLong(callbackData.split(":", 2)[1]);
                handleCancelTask(chatId, messageId, taskId);
            } catch (NumberFormatException e) {
                log.error("Invalid task ID in cancel callback: {}", callbackData);
            }
            return;
        }

        if (callbackData.startsWith("TRAIN:")) {
            String selectedTrain = callbackData.substring("TRAIN:".length());
            TrackingTask draft = taskDrafts.remove(chatId);
            if (draft == null) {
                sendText(chatId, "Сессия устарела. Начните поиск заново: /new");
                return;
            }

            draft.setTrainNumber(selectedTrain);
            draft.setStatus(TaskStatus.ACTIVE);
            trackingTaskRepository.save(draft);

            if (user != null) {
                user.setBotState("IDLE");
                userRepository.save(user);
            }

            String trainDisplay = selectedTrain.equalsIgnoreCase("ANY") ? "Любой" : selectedTrain;
            sendText(chatId, String.format(
                    "Отслеживание запущено.\n\n" +
                            "Маршрут: %s → %s\n" +
                            "Дата: %s\n" +
                            "Поезд: %s\n" +
                            "Критерий: Плацкарт, только нижние полки\n\n" +
                            "Как только место появится, вам придет оповещение.",
                    draft.getOriginStationName(),
                    draft.getDestinationStationName(),
                    draft.getDepartureDate(),
                    trainDisplay
            ));
            return;
        }

        if (user == null) {
            return;
        }

        String[] parts = callbackData.split(":", 3);
        if (parts.length != 3) {
            return;
        }

        String prefix = parts[0];
        String code = parts[1];
        String name = parts[2];

        TrackingTask draft = taskDrafts.computeIfAbsent(chatId, k -> new TrackingTask());

        if ("ORIGIN".equals(prefix)) {
            draft.setChatId(chatId);
            draft.setOriginCode(code);
            draft.setOriginStationName(name);

            user.setBotState("AWAITING_DEST_INPUT");
            userRepository.save(user);
            sendText(chatId, "Отправление: " + name + "\n\nВведите город или станцию назначения (например, Санкт-Петербург):");

        } else if ("DEST".equals(prefix)) {
            draft.setDestinationCode(code);
            draft.setDestinationStationName(name);

            user.setBotState("AWAITING_DATE");
            userRepository.save(user);
            sendText(chatId, "Назначение: " + name + "\n\nВведите дату поездки в формате ГГГГ-ММ-ДД (например: 2026-10-15):");
        }
    }

    private void handleDateInput(long chatId, String text, User user) {
        try {
            LocalDate date = LocalDate.parse(text);
            if (date.isBefore(LocalDate.now())) {
                sendText(chatId, "Дата не может быть в прошлом. Введите дату в формате ГГГГ-ММ-ДД:");
                return;
            }

            TrackingTask draft = taskDrafts.get(chatId);
            if (draft == null || draft.getOriginCode() == null || draft.getDestinationCode() == null) {
                sendText(chatId, "Сессия устарела. Начните поиск заново: /new");
                return;
            }

            draft.setDepartureDate(date);
            draft.setCarType("ПЛАЦКАРТ_НИЖНИЕ");

            String formattedDate = date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            List<TrainOption> trains = rzdService.getAvailableTrains(draft.getOriginCode(), draft.getDestinationCode(), formattedDate);

            if (trains.isEmpty()) {
                sendText(chatId, "На выбранную дату прямых поездов не найдено. Попробуйте другую дату:");
                return;
            }

            user.setBotState("AWAITING_TRAIN_SELECTION");
            userRepository.save(user);

            SendMessage message = new SendMessage(String.valueOf(chatId), "Выберите поезд для мониторинга:");
            message.setReplyMarkup(KeyboardFactory.createTrainPicker(trains));
            executeSafely(message);

        } catch (DateTimeParseException e) {
            sendText(chatId, "Неверный формат даты. Введите дату в виде ГГГГ-ММ-ДД (например, 2026-10-15):");
        }
    }

    private void showActiveTasks(long chatId) {
        List<TrackingTask> tasks = trackingTaskRepository.findAllByChatIdAndStatus(chatId, TaskStatus.ACTIVE);
        if (tasks.isEmpty()) {
            sendText(chatId, "У вас нет активных отслеживаний.\nСоздать новый поиск: /new");
            return;
        }

        sendText(chatId, "Ваши активные задачи отслеживания:");

        for (TrackingTask task : tasks) {
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText(String.format(
                    "Задача #%d\n" +
                            "Маршрут: %s → %s\n" +
                            "Дата поездки: %s\n" +
                            "Поезд: %s\n" +
                            "Тип мест: %s",
                    task.getId(),
                    task.getOriginStationName(),
                    task.getDestinationStationName(),
                    task.getDepartureDate(),
                    task.getTrainNumber() != null ? task.getTrainNumber() : "Любой",
                    task.getCarType() != null ? task.getCarType() : "Любой"
            ));
            message.setReplyMarkup(KeyboardFactory.createCancelTaskKeyboard(task.getId()));
            executeSafely(message);
        }
    }

    private void handleCancelTask(long chatId, Integer messageId, Long taskId) {
        Optional<TrackingTask> taskOpt = trackingTaskRepository.findById(taskId);

        if (taskOpt.isEmpty()) {
            sendText(chatId, "Задача не найдена.");
            return;
        }

        TrackingTask task = taskOpt.get();
        if (!task.getChatId().equals(chatId)) {
            sendText(chatId, "У вас нет прав на отмену этой задачи.");
            return;
        }

        if (task.getStatus() != TaskStatus.ACTIVE) {
            sendText(chatId, "Эта задача уже не активна.");
            return;
        }

        task.setStatus(TaskStatus.CANCELLED);
        trackingTaskRepository.save(task);

        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(String.valueOf(chatId));
        editMessage.setMessageId(messageId);
        editMessage.setText(String.format(
                "Задача #%d отменена\nМаршрут: %s → %s (%s)",
                task.getId(),
                task.getOriginStationName(),
                task.getDestinationStationName(),
                task.getDepartureDate()
        ));
        editMessage.setReplyMarkup(null);

        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to edit cancel message: {}", e.getMessage());
        }
    }

    public void sendText(long chatId, String text) {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        executeSafely(message);
    }

    private void executeSafely(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
        }
    }
}