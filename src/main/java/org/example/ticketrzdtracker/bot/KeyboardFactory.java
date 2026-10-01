package org.example.ticketrzdtracker.bot;

import org.example.ticketrzdtracker.model.dto.StationSuggestion;
import org.example.ticketrzdtracker.model.dto.TrainOption;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.*;

public final class KeyboardFactory {

    private KeyboardFactory() {}

    public static InlineKeyboardMarkup createStationPicker(List<StationSuggestion> suggestions, String callbackPrefix) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        int count = 0;
        for (StationSuggestion station : suggestions) {
            if (station.getName() == null || station.getName().isBlank()
                    || station.getCode() == null || station.getCode().isBlank()) {
                continue;
            }

            if (!seenCodes.add(station.getCode())) {
                continue;
            }

            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(station.getDisplayName());
            button.setCallbackData(callbackPrefix + ":" + station.getCode() + ":" + station.getName());
            keyboard.add(Collections.singletonList(button));

            if (++count >= 5) {
                break;
            }
        }

        if (keyboard.isEmpty()) {
            return null;
        }

        markup.setKeyboard(keyboard);
        return markup;
    }

    public static InlineKeyboardMarkup createTrainPicker(List<TrainOption> trains) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        InlineKeyboardButton anyButton = new InlineKeyboardButton();
        anyButton.setText("Любой поезд");
        anyButton.setCallbackData("TRAIN:ANY");
        keyboard.add(Collections.singletonList(anyButton));

        for (TrainOption train : trains) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(String.format("№%s (%s → %s)", train.getTrainNumber(), train.getDepartureTime(), train.getArrivalTime()));
            button.setCallbackData("TRAIN:" + train.getTrainNumber());
            keyboard.add(Collections.singletonList(button));
        }

        markup.setKeyboard(keyboard);
        return markup;
    }

    public static InlineKeyboardMarkup createCancelTaskKeyboard(Long taskId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Отменить отслеживание");
        button.setCallbackData("CANCEL:" + taskId);
        markup.setKeyboard(Collections.singletonList(Collections.singletonList(button)));
        return markup;
    }
}