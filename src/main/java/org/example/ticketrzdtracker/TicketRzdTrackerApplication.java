package org.example.ticketrzdtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketRzdTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketRzdTrackerApplication.class, args);
    }
}
