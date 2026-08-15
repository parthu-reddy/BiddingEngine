package com.fooddelivery.advertisement.bidding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication(scanBasePackages = {"com.fooddelivery.advertisement.bidding", "com.fooddelivery.common", "com.fooddelivery"})
public class BiddingEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(BiddingEngineApplication.class, args);
    }
}
