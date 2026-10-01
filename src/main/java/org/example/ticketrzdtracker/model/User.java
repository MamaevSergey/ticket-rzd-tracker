package org.example.ticketrzdtracker.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {
    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "username")
    private String username;

    @Column(name = "bot_state", nullable = false, length = 64)
    private String botState = "START";

    @Column(name = "temp_session_data", columnDefinition = "TEXT")
    private String tempSessionData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public User(Long chatId, String username) {
        this.chatId = chatId;
        this.username = username;
        this.botState = "START";
        this.createdAt = OffsetDateTime.now();
    }
}