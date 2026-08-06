package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;

public interface TargetingFilter {
    boolean evaluate(BidRequest request, CampaignIndexData campaignData);
}
