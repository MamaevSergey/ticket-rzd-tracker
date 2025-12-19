package org.example.ticketrzdtracker.bot;

import org.apache.commons.lang3.StringUtils;
import org.example.ticketrzdtracker.model.UserSession;
import org.example.ticketrzdtracker.model.UserState;
import org.example.ticketrzdtracker.service.TrackingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class RzdTelegramBot extends TelegramLongPollingBot {
    private final TrackingService trackingService;

    @Value("${bot.name}")
    private String botName;
    @Value("${bot.token}")
    private String botToken;
    @Value("${bot.secret-password}")
    private String secretPassword;

    public RzdTelegramBot(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @Override
    public String getBotUsername() { return botName; }
    @Override
    public String getBotToken() { return botToken; }

    @Override
    public void onUpdateReceived(Update update) {

        // Добавлено только что
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String msg = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        UserSession session = trackingService.getSession(chatId);
        String username = update.getMessage().getFrom().getUserName();
        if (msg.equals("/start")) {
            session.setState(UserState.START);
            session.setPasswordAttempts(0);
            String text = """
                    Привет! Я бот для отслеживания нижних мест в поезде РЖД!
                    Надеюсь у меня получится тебе помочь!
                    """;
            sendInlineKeyboard(chatId, "Привет! Я бот для поиска билетов.", "Начать сессию", "CMD_START_SESSION");
            return;
        }

        switch (session.getState()) {
            case START:
                sendInlineKeyboard(chatId, "Нажмите кнопку ниже, чтобы начать.", "Начать сессию", "CMD_START_SESSION");
                break;

            case AWAITING_PASSWORD:
                if (msg.equals(secretPassword)) {
                    session.setState(UserState.AWAITING_DATA);
                    String text = String.format("""
                            Пароль принят!
                            Введите данные в формате:
                            Откуда, Куда, Дата(дд.мм.гггг), Время(секунд)
                            
                            Пример: "Екатеринбург-Пассажирс, Соликамск, 22.12.2025, 3600"
                            """);
                    sendMessage(chatId, text);

                } else {
                    session.setPasswordAttempts(session.getPasswordAttempts() + 1);
                    if (session.getPasswordAttempts() >= 3) {
                        session.setState(UserState.START);
                        sendMessage(chatId, "⛔ Попытки исчерпаны.");
                        sendInlineKeyboard(chatId, "Попробуйте заново?", "Начать сессию", "CMD_START_SESSION");
                    } else {
                        sendMessage(chatId, "Неверно. Осталось попыток: " + (3 - session.getPasswordAttempts()));
                    }
                }
                break;

            case AWAITING_DATA:
                try {
                    String[] parts = msg.split(", ");

                    if (parts.length < 4) throw new IllegalArgumentException();

                    String from = parts[0];
                    String to = parts[1];
                    LocalDate.parse(parts[2], DateTimeFormatter.ofPattern("dd.MM.yyyy"));

                    int seconds = Integer.parseInt(parts[3]);

                    if (seconds > 86400) {
                        seconds = 86400;
                        String text = String.format("""
                                Максимум можно установить трекинг на 24 часа.
                                Ставлю максимальное значение - 86400 секунд.
                                """);
                        sendMessage(chatId, text);
                    }

                    sendMessage(chatId, "Данные приняты. Ищу вокзалы и поезд...");

                    boolean isStarted = trackingService.startTracking(chatId, from, to, parts[2], seconds);

                    if (isStarted) {
                        sendInlineKeyboard(chatId, "🚀 Мониторинг успешно запущен!", "Остановить", "CMD_STOP_MONITORING");
                    } else {
                        // Если не запустилось (не нашел город/поезд), клавиатуру НЕ меняем или возвращаем старую
                        sendMessage(chatId, "Попробуйте ввести данные еще раз корректно.");
                    }
                } catch (Exception exception) {
                    String text = String.format("""
                            Упс. Кажется возникла ошибка формата!
                            Используйте правильный ввод.
                            
                            Пример, как нужно: "Город1, Город2, 22.12.2025, 3600"
                            """);
                    sendMessage(chatId, text);
                }
                break;

            case TRACKING:
                if (msg.equals("Остановить мониторинг")) {
                    sendInlineKeyboard(chatId, "Мониторинг активен!", "Остановить", "CMD_STOP_MONITORING");
                }
                break;

            case AUTHENTICATED:
                if (msg.equals("Начать сессию")) {
                    session.setState(UserState.AWAITING_DATA);
                    String text = String.format("""
                            %s, с возвращением!!
                            Откуда, Куда, Дата(дд.мм.гггг), Время(секунд)
                            
                            Пример: "Екатеринбург-Пассажирс, Соликамск, 22.12.2025, 3600"
                            """, StringUtils.capitalize(username));
                    sendMessage(chatId, text);
                } else {
                    String text = String.format("""
                            Не понимаю, что вы имеете ввиду...
                            Если вы хотите продолжить, нажмите на кнопку.
                            """);
                    sendInlineKeyboard(chatId, text, "Начать сессию", "CMD_START_SESSION");
                }
        }
    }

    public void sendMessage(Long chatId, String text) {
        try {
            execute(new SendMessage(chatId.toString(), text));
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public void sendReplyKeyboard(Long chatId, String text, List<String> buttons) {
        SendMessage message = new SendMessage(chatId.toString(), text);
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        buttons.forEach(row::add);
        keyboard.add(row);
        keyboardMarkup.setKeyboard(keyboard);
        keyboardMarkup.setResizeKeyboard(true);
        message.setReplyMarkup(keyboardMarkup);
        try {
            execute(message);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void handleCallback(CallbackQuery callback) {
        String data = callback.getData();
        Long chatId = callback.getMessage().getChatId();
        UserSession session = trackingService.getSession(chatId);

        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callback.getId());
        try { execute(answer); } catch (Exception e) {}

        if (data.equals("CMD_START_SESSION")) {
            session.setState(UserState.AWAITING_PASSWORD);
            sendMessage(chatId, "\uD83D\uDD12 Введите пароль доступа:");
        }

        else if (data.equals("CMD_STOP_MONITORING")) {
            trackingService.stopTracking(chatId);
            session.setState(UserState.AUTHENTICATED);
            sendMessage(chatId, "\uD83D\uDED1 Мониторинг остановлен.");
            sendInlineKeyboard(chatId, "Хотите найти другой билет?", "Начать сессию", "CMD_START_SESSION");
        }
    }

    public void sendInlineKeyboard(Long chatId, String text, String btnText, String btnCallbackData) {
        SendMessage message = new SendMessage(chatId.toString(), text);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        var button = new InlineKeyboardButton();
        button.setText(btnText);
        button.setCallbackData(btnCallbackData);

        row.add(button);
        rows.add(row);
        markup.setKeyboard(rows);

        message.setReplyMarkup(markup);
        try {
            execute(message);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

}
