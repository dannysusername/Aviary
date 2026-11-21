package com.example.AviaryService.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// The tools an assistant sees on /mcp, and the parameters spec 012 added.
@SpringBootTest
@ActiveProfiles("test")
class McpToolListTest {

    @Autowired ToolCallbackProvider aviaryTools;

    private ToolCallback tool(String name) {
        return Arrays.stream(aviaryTools.getToolCallbacks())
            .filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void toolList() {
        List<String> names = Arrays.stream(aviaryTools.getToolCallbacks())
            .map(t -> t.getToolDefinition().name()).sorted().toList();
        assertEquals(List.of("add_aircraft", "confirm_status_sheet_import", "continue_poor_fit", "get_import_format",
            "list_aircraft", "list_flights", "list_status_sheet", "propose_status_sheet_import"), names);
    }

    @Test
    void newParametersAreAdvertised() {
        String add = tool("add_aircraft").getToolDefinition().inputSchema();
        assertTrue(add.contains("tailNumber") && add.contains("makeModel"), add);
        String propose = tool("propose_status_sheet_import").getToolDefinition().inputSchema();
        assertTrue(propose.contains("recordsTailNumber"), propose);
    }
}
