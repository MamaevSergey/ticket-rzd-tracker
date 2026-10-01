package org.example.ticketrzdtracker.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticketrzdtracker.bot.RzdTelegramBot;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BotInitializer {

    private final RzdTelegramBot bot;

    @EventListener({ContextRefreshedEvent.class})
    public void init() {
        try {
            TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            telegramBotsApi.registerBot(bot);
            log.info("Telegram-бот успешно зарегистрирован");
            registerBotCommands();

        } catch (TelegramApiException e) {
            log.error("Ошибка при инициализации бота: {}", e.getMessage());
        }
    }

    private void registerBotCommands() {
        List<BotCommand> commands = List.of(
                new BotCommand("/new", "Начать поиск и отслеживание билетов"),
                new BotCommand("/tasks", "Текущее активное отслеживание"),
                new BotCommand("/cancel", "Отменить текущий мониторинг"),
                new BotCommand("/start", "Перезапустить диалог")
        );

        try {
            bot.execute(new SetMyCommands(commands, new BotCommandScopeDefault(), null));
            log.info("Команды меню Telegram успешно обновлены");
        } catch (TelegramApiException e) {
            log.warn("Не удалось установить команды меню: {}", e.getMessage());
        }
    }
}