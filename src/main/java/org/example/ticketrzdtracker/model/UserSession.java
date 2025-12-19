package org.example.ticketrzdtracker.model;

import lombok.Data;

import java.util.concurrent.ScheduledFuture;

@Data
public class UserSession {
    private Long chatId;
    private UserState state = UserState.START;
    private int passwordAttempts = 0;

    private TrainSessionData trainData;

    private ScheduledFuture<?> trackingTask;
    private long trackingEndTime;
}
