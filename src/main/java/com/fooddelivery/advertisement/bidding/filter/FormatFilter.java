package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.stereotype.Component;

@Component
public class FormatFilter implements TargetingFilter {
    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        // Validate that campaign format matches impression format
        // Mocked check for now, returning true
        return true;
    }
}
