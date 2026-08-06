package com.fooddelivery.advertisement.bidding.pricing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import org.springframework.stereotype.Component;

@Component
class FirstPriceShadedStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculateBid(String campaignId, BigDecimal maxBid, double pacingS) {
        // Applies Bid Shading logic
        // Reduces max bid based on pacing and predicted market clearing price
        BigDecimal shaded = maxBid.multiply(new BigDecimal(pacingS)).multiply(BiddingConstants.SHADING_FACTOR);
        return shaded.setScale(4, RoundingMode.HALF_UP);
    }
}
