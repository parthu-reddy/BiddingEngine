package com.fooddelivery.advertisement.bidding.model;

public class CampaignIndexData {
    public String campaignId;
    public String advertiserId;
    
    // We can add targeting settings here in the future if we load them into memory
    // For now we pass this wrapper down
    public CampaignIndexData(String campaignId, String advertiserId) {
        this.campaignId = campaignId;
        this.advertiserId = advertiserId;
    }
}
