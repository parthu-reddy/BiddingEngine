package com.fooddelivery.advertisement.bidding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.fooddelivery.advertisement.bidding", "com.fooddelivery.common"})
public class BiddingEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(BiddingEngineApplication.class, args);
    }
}
