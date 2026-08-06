package com.fooddelivery.advertisement.bidding.pricing;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

public interface PricingStrategy {
    BigDecimal calculateBid(String campaignId, BigDecimal maxBid, double pacingS);
}
