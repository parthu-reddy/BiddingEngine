package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;

import org.springframework.stereotype.Component;
import com.fooddelivery.advertisement.bidding.cache.CacheProvider;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

@Component
@Order(2)
@lombok.extern.slf4j.Slf4j
public class PacingFilter implements TargetingFilter {

    public PacingFilter() { }
    
    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        double pacingS = campaignData.pacingMultiplier;
        
        if (pacingS < 0) {
            // Default or unknown pacing, treat as 1.0
            pacingS = 1.0;
        }

        if (pacingS >= 1.0) {
            return true; // No throttling needed
        }
        if (pacingS <= 0.0) {
            return false; // Exhausted or paused
        }
        
        // Probabilistic gating: Only bid if a random roll is less than the pacing multiplier S
        double roll = ThreadLocalRandom.current().nextDouble();
        return roll <= pacingS;
    }
}

