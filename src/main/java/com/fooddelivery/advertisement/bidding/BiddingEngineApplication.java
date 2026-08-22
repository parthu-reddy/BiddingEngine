package com.fooddelivery.advertisement.bidding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(
    scanBasePackages = {"com.fooddelivery.advertisement.bidding", "com.fooddelivery.common"}
)
@EnableFeignClients
public class BiddingEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(BiddingEngineApplication.class, args);
    }
}
