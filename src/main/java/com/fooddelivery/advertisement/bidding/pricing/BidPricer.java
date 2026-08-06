package com.fooddelivery.advertisement.bidding.pricing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class BidPricer {

    private final Map<String, PricingStrategy> strategies = new HashMap<>();

    public BidPricer(List<PricingStrategy> strategyList) {
        for (PricingStrategy s : strategyList) {
            strategies.put(s.getClass().getSimpleName(), s);
        }
    }

    public BigDecimal price(PricingStrategyType strategyType, String campaignId, BigDecimal maxBid, double pacingS) {
        PricingStrategy strategy = strategies.getOrDefault(strategyType.getBeanName(), strategies.get(PricingStrategyType.FIRST_PRICE_SHADED_STRATEGY.getBeanName()));
        return strategy.calculateBid(campaignId, maxBid, pacingS);
    }
}
