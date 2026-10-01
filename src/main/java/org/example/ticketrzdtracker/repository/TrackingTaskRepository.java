package org.example.ticketrzdtracker.repository;

import org.example.ticketrzdtracker.model.TaskStatus;
import org.example.ticketrzdtracker.model.TrackingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TrackingTaskRepository extends JpaRepository<TrackingTask, Long> {
    List<TrackingTask> findAllByStatus(TaskStatus status);
    List<TrackingTask> findAllByChatIdAndStatus(Long chatId, TaskStatus status);
    List<TrackingTask> findAllByStatusAndDepartureDateBefore(TaskStatus status, LocalDate date);
}