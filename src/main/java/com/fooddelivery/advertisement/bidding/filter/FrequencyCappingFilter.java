package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(5) // Ensure it runs after cheaper filters like Geo
public class FrequencyCappingFilter implements TargetingFilter {

    private final StringRedisTemplate redisTemplate;
    private static final int MAX_DAILY_IMPRESSIONS = 5;

    public FrequencyCappingFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaign) {
        String identifier = null;
        if (request.user != null && request.user.id != null) {
            identifier = request.user.id;
        } else if (request.user != null && request.user.buyeruid != null) {
            identifier = request.user.buyeruid;
        }

        if (identifier == null) {
            // Can't cap if we don't know who it is. Allow it.
            return true;
        }

        String capKey = "ad:cap:" + identifier + ":" + campaign.campaignId;
        String countStr = redisTemplate.opsForValue().get(capKey);
        
        if (countStr != null) {
            try {
                int count = Integer.parseInt(countStr);
                
                int limit = MAX_DAILY_IMPRESSIONS;
                if (campaign.targeting != null && campaign.targeting.getFrequencyCap() != null) {
                    limit = campaign.targeting.getFrequencyCap();
                }

                if (count >= limit) {
                    return false; // Filter out, cap exceeded
                }
            } catch (NumberFormatException ignored) {}
        }
        
        return true;
    }
}
