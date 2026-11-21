package com.example.AviaryService.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

// Spec 005: the Java fields were renamed to hobbsTime*, but their data stays
// in the existing block_time_* columns. If someone drops an @Column(name=...)
// pin, ddl-auto=update silently adds a new, EMPTY hobbs_time_* column and every
// owner sees 0 hours (the Sept 2026 rename bug). This test catches that.
@SpringBootTest
@ActiveProfiles("test")
class HobbsColumnMappingTest {

    @Autowired
    private JdbcTemplate jdbc;

    private List<String> columns(String table) {
        return jdbc.queryForList(
            "SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = ?",
            String.class, table);
    }

    @Test
    void aircraftHobbsFieldsUseExistingBlockTimeColumns() {
        List<String> cols = columns("aircraft");
        for (String c : List.of("block_time_hours", "block_time_manual_baseline",
                "block_time_updated_at", "block_time_updated_source")) {
            assertTrue(cols.contains(c), "missing column " + c);
        }
        assertEquals(0, cols.stream().filter(c -> c.startsWith("hobbs_time")).count(),
            "a hobbs_time_* column was created; pin it to block_time_* instead");
    }

    @Test
    void flightLogHobbsFieldsUseExistingBlockTimeColumns() {
        List<String> cols = columns("flight_logs");
        for (String c : List.of("block_time_in", "block_time_out", "block_time_start", "block_time_end")) {
            assertTrue(cols.contains(c), "missing column " + c);
        }
        assertEquals(0, cols.stream().filter(c -> c.startsWith("hobbs_time")).count(),
            "a hobbs_time_* column was created; pin it to block_time_* instead");
    }
}
