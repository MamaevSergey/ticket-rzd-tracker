package org.example.ticketrzdtracker.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tracking_tasks")
@Getter
@Setter
@NoArgsConstructor
public class TrackingTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "origin_code", nullable = false, length = 16)
    private String originCode;

    @Column(name = "origin_station_name")
    private String originStationName;

    @Column(name = "destination_code", nullable = false, length = 16)
    private String destinationCode;

    @Column(name = "destination_station_name")
    private String destinationStationName;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "train_number", length = 32)
    private String trainNumber;

    @Column(name = "car_type", length = 64)
    private String carType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status = TaskStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;
}