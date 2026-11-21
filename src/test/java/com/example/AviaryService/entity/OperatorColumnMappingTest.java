package com.example.AviaryService.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

// Spec 009: Aircraft.ownerName was renamed to operatorName in code, but the
// names stay in the existing owner_name column. Dropping the @Column pin would
// make ddl-auto create an empty operator_name column and every aircraft would
// show a blank Operator. This test catches that.
@SpringBootTest
@ActiveProfiles("test")
class OperatorColumnMappingTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void operatorNameUsesTheExistingOwnerNameColumn() {
        List<String> cols = jdbc.queryForList(
            "SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'aircraft'",
            String.class);
        assertTrue(cols.contains("owner_name"), "missing aircraft.owner_name");
        assertEquals(0, cols.stream().filter(c -> c.startsWith("operator")).count(),
            "an operator* column was created; pin @Column(name = \"owner_name\") instead");
    }
}
