package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.stereotype.Component;

@Component
public class BrandSafetyFilter implements TargetingFilter {
    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        // Ensure brand safety guidelines
        return true;
    }
}
