package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.stereotype.Component;

@Component
public class GeoDemographicFilter implements TargetingFilter {
    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        // Mock demographic filter, already handled by RoaringBitmap partly
        return true;
    }
}
