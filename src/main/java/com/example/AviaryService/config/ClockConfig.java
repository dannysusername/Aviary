package com.example.AviaryService.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The app's "now". Alerts read time from this so tests can move it forward
// day by day (spec 014). UTC; each owner's own zone is applied on top.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
