package com.waliot.geo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GeocodingApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeocodingApplication.class, args);
    }
}
