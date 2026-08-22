package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(5)
public class BrandSafetyBlocklistFilter implements TargetingFilter {

    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        if (campaignData.targeting == null || campaignData.targeting.getBrandSafetyBlocklist() == null || campaignData.targeting.getBrandSafetyBlocklist().isEmpty()) {
            return true;
        }

        if (request.context == null || request.context.isEmpty()) {
            // If the campaign has a blocklist, but the request has no context, we allow it (or block it?)
            // Usually, brand safety is blocked if context matches. If no context, it doesn't match the blocklist.
            return true;
        }

        List<String> blocklist = campaignData.targeting.getBrandSafetyBlocklist();
        for (String blocklistedContext : blocklist) {
            if (request.context.equalsIgnoreCase(blocklistedContext)) {
                return false; // Blocked because request context matches a blocklisted context
            }
        }
        
        return true;
    }
}
