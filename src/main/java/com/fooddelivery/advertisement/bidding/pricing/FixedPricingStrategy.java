package com.fooddelivery.advertisement.bidding.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
class FixedPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculateBid(String campaignId, BigDecimal maxBid, double pacingS) {
        // Fixed price means we just bid the maxBid
        return maxBid.setScale(4, RoundingMode.HALF_UP);
    }
}
