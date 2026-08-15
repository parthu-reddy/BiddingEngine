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

@Component
@lombok.extern.slf4j.Slf4j
public class PacingFilter implements TargetingFilter {

    private final CacheProvider cache;
    
    public PacingFilter(CacheProvider cache) { this.cache = cache; }
    
    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        String pacingStr = cache.get(String.format(RedisKeyConstants.PREFIX_AD_CAMPAIGN_PACING, campaignData.campaignId))
            .block(java.time.Duration.ofMillis(50));
            
        if (pacingStr == null) {
            pacingStr = BiddingConstants.DEFAULT_PACING_MULTIPLIER_STRING;
        }
        
        try {
            double pacingS = Double.parseDouble(pacingStr);
            if (pacingS >= 1.0) {
                return true; // No throttling needed
            }
            if (pacingS <= 0.0) {
                return false; // Exhausted or paused
            }
            
            // Probabilistic gating: Only bid if a random roll is less than the pacing multiplier S
            double roll = ThreadLocalRandom.current().nextDouble();
            return roll <= pacingS;
            
        } catch (NumberFormatException e) {
            log.error("Invalid pacing multiplier '{}' for campaign {}", pacingStr, campaignData.campaignId);
            return true; // Fall open
        }
    }
}

