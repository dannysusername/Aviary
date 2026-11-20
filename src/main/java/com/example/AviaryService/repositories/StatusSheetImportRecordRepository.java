package com.example.AviaryService.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.AviaryService.entity.StatusSheetImportRecord;
import com.example.AviaryService.entity.User;

public interface StatusSheetImportRecordRepository extends JpaRepository<StatusSheetImportRecord, Long> {
    List<StatusSheetImportRecord> findTop20ByUserOrderByImportedAtDesc(User user);
}
