package com.fooddelivery.advertisement.bidding.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
class SecondPriceStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculateBid(String campaignId, BigDecimal maxBid, double pacingS) {
        // Second price relies on the auction clearing price, but we bid our true value reduced by pacing
        BigDecimal bid = maxBid.multiply(new BigDecimal(pacingS));
        return bid.setScale(4, RoundingMode.HALF_UP);
    }
}
