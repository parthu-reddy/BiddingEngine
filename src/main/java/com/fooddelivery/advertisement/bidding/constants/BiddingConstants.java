package com.fooddelivery.advertisement.bidding.constants;

import java.math.BigDecimal;

public final class BiddingConstants {
    private BiddingConstants() {}

    public static final String DEFAULT_GEO = "US";
    public static final String PREFIX_BID_ID = "bid-";
    public static final String PREFIX_AD_ID = "ad-";
    public static final String DEFAULT_PACING_MULTIPLIER_STRING = "1.0";
    
    public static final BigDecimal DEFAULT_MAX_BID = new BigDecimal("5.0000");
    public static final BigDecimal SHADING_FACTOR = new BigDecimal("0.85");
}
