package org.example.ticketrzdtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TicketResult {
    private String carNumber;
    private int seatNumber;
    private double price;
}
