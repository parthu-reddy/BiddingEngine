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
    /**
     * The advertiser's IANA zone. Dayparting reads the advertiser's wall clock in it, and it is signed
     * into the auction token so the tracker books spend to the advertiser's day.
     */
    public String timeZone;
    
    public CampaignIndexData(String campaignId, String advertiserId, java.math.BigDecimal maxBid, double pacingMultiplier, boolean budgetExhausted, com.fooddelivery.common.dto.targeting.TargetingSummary targeting, String creativeFormat, String creativeAssetUrl, String creativeVastXml, String timeZone) {
        this.campaignId = campaignId;
        this.advertiserId = advertiserId;
        this.maxBid = maxBid;
        this.pacingMultiplier = pacingMultiplier;
        this.budgetExhausted = budgetExhausted;
        this.targeting = targeting;
        this.creativeFormat = creativeFormat;
        this.creativeAssetUrl = creativeAssetUrl;
        this.creativeVastXml = creativeVastXml;
        this.timeZone = timeZone;
    }
}
