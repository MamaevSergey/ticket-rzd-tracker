package org.example.ticketrzdtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TrainSessionData {
    private String originCode;
    private String destinationCode;
    private String departureDate;
    private String trainNumber;
    private String provider;
}
