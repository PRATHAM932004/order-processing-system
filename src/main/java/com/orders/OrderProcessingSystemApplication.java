package com.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderProcessingSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessingSystemApplication.class, args);
    }
}
