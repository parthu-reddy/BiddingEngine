package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
public class BrandSafetyFilter implements TargetingFilter {
    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        // As BrandSafety Blocklist was moved to AdGroup, it's not present in TargetingSummary
        // For Phase 6.4 we just add it to the chain and evaluate to true for now since BidRequest 
        // doesn't have publisher details to match against blocklists.
        return true;
    }
}
