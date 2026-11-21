package com.example.AviaryService.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

// Spec 006: Service Timeline was renamed to Status Sheet in code, but the
// data stays in the existing service_timelines / timeline_import_records
// tables and the timeline_order column. If a @Table/@Column pin is dropped,
// ddl-auto=update creates a new, EMPTY table or column and every owner's
// Status Sheet looks empty or unordered. This test catches that.
@SpringBootTest
@ActiveProfiles("test")
class StatusSheetColumnMappingTest {

    @Autowired
    private JdbcTemplate jdbc;

    private List<String> tables() {
        return jdbc.queryForList(
            "SELECT LOWER(table_name) FROM information_schema.tables WHERE LOWER(table_schema) = 'public'",
            String.class);
    }

    @Test
    void statusSheetUsesExistingTables() {
        List<String> t = tables();
        assertTrue(t.contains("service_timelines"), "missing service_timelines");
        assertTrue(t.contains("timeline_import_records"), "missing timeline_import_records");
        assertEquals(0, t.stream().filter(n -> n.contains("status_sheet")).count(),
            "a status_sheet* table was created; pin @Table to the old name instead");
    }

    @Test
    void statusSheetOrderUsesExistingColumn() {
        List<String> cols = jdbc.queryForList(
            "SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'service_timelines'",
            String.class);
        assertTrue(cols.contains("timeline_order"), "missing timeline_order");
        assertEquals(0, cols.stream().filter(c -> c.contains("status_sheet")).count(),
            "a status_sheet* column was created; pin @Column to timeline_order instead");
    }
}
