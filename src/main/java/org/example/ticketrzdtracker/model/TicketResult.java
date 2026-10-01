package org.example.ticketrzdtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TicketResult {
    private String carNumber;
    private int seatNumber;
    private double price;
    private String placeTypeName;
}