package com.orbitstorage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrbitStorageApplication {
    public static void main(String[] args) { SpringApplication.run(OrbitStorageApplication.class, args); }
}
