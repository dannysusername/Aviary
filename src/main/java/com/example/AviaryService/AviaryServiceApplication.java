package com.example.AviaryService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AviaryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AviaryServiceApplication.class, args);
	}

	/*
	1. JVM starts — main() is the entry point
		AviaryServiceApplication.java:9

		SpringApplication.run(AviaryServiceApplication.class, args);
		@SpringBootApplication is shorthand for three annotations combined:

		@SpringBootConfiguration — this is a config class
		@EnableAutoConfiguration — auto-wire common things (web server, DB, etc.)
		@ComponentScan — scan this package and subpackages for @Controller, @Service, @Repository, etc.
		Spring then scans every class in com.example.AviaryService and wires everything together.
	
	*/

}
