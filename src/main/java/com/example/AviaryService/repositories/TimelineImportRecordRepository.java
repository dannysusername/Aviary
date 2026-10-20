package com.example.AviaryService.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.AviaryService.entity.TimelineImportRecord;
import com.example.AviaryService.entity.User;

public interface TimelineImportRecordRepository extends JpaRepository<TimelineImportRecord, Long> {
    List<TimelineImportRecord> findTop20ByUserOrderByImportedAtDesc(User user);
}
