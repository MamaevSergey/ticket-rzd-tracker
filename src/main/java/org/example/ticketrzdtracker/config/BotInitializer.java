package org.example.ticketrzdtracker.config;

import org.example.ticketrzdtracker.bot.RzdTelegramBot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
public class BotInitializer {

    @Bean
    public TelegramBotsApi telegramBotsApi(RzdTelegramBot rzdTelegramBot) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(rzdTelegramBot);
        return api;
    }

}
