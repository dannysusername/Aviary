package com.example.AviaryService.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentAircraftResolver currentAircraftResolver;

    public WebConfig(CurrentAircraftResolver currentAircraftResolver) {
        this.currentAircraftResolver = currentAircraftResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentAircraftResolver);
    }
}
