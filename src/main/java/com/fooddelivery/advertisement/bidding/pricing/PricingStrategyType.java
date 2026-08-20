package com.fooddelivery.advertisement.bidding.pricing;

public enum PricingStrategyType {
    FIRST_PRICE_SHADED_STRATEGY("FirstPriceShadedStrategy");

    private final String beanName;

    PricingStrategyType(String beanName) {
        this.beanName = beanName;
    }

    public String getBeanName() {
        return beanName;
    }
}
