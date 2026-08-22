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
        // Note: For internal traffic, request.user.id is explicitly set to deviceId by InternalAdController, 
        // matching the ad:cap:{deviceId}:{campaignId} keys written by UserTrackingService.
        // For OpenRTB traffic, user.id is the exchange-supplied identifier. RTB traffic is NOT capped 
        // by us internally (since identity spaces differ), relying on the exchange's capping instead.
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
