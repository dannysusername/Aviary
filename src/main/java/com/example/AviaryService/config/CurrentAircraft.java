package com.example.AviaryService.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks a controller parameter that receives the aircraft the request was
// made from, already ownership-checked. See CurrentAircraftResolver.
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAircraft {
}
