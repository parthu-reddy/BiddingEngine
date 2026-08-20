package com.fooddelivery.advertisement.bidding.model;

public class CampaignIndexData {
    public String campaignId;
    public String advertiserId;
    public java.math.BigDecimal maxBid;
    public double pacingMultiplier;
    public boolean budgetExhausted;
    public com.fooddelivery.common.dto.targeting.TargetingSummary targeting;
    public String creativeFormat;
    public String creativeAssetUrl;
    public String creativeVastXml;
    
    public CampaignIndexData(String campaignId, String advertiserId, java.math.BigDecimal maxBid, double pacingMultiplier, boolean budgetExhausted, com.fooddelivery.common.dto.targeting.TargetingSummary targeting, String creativeFormat, String creativeAssetUrl, String creativeVastXml) {
        this.campaignId = campaignId;
        this.advertiserId = advertiserId;
        this.maxBid = maxBid;
        this.pacingMultiplier = pacingMultiplier;
        this.budgetExhausted = budgetExhausted;
        this.targeting = targeting;
        this.creativeFormat = creativeFormat;
        this.creativeAssetUrl = creativeAssetUrl;
        this.creativeVastXml = creativeVastXml;
    }
}
