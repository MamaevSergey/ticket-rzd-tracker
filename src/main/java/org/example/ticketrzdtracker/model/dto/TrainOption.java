package org.example.ticketrzdtracker.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrainOption {
    private String trainNumber;
    private String departureTime;
    private String arrivalTime;
    private String provider;
    private String departureDateTime;
}