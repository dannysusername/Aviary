package com.example.AviaryService.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration 
public class McpConfig {

    @Bean 
    ToolCallbackProvider aviaryTools(FlightTools flightTools, StatusSheetImportTools statusSheetImportTools,
            AircraftTools aircraftTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(flightTools, statusSheetImportTools, aircraftTools).build();
    }
    
}
