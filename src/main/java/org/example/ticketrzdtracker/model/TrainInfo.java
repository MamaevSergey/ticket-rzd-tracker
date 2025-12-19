package org.example.ticketrzdtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TrainInfo {
    private String number;
    private String exactDepartureTime;
}
