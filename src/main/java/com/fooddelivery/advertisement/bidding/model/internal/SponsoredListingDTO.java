package com.fooddelivery.advertisement.bidding.model.internal;

public class SponsoredListingDTO {
    public String adId;
    public String campaignId;
    public String impressionUrl;
    public String clickUrl;
    public String adm;
    public String creativeFormat;
    
    public SponsoredListingDTO(String adId, String campaignId, String impressionUrl, String clickUrl, String adm, String creativeFormat) {
        this.adId = adId;
        this.campaignId = campaignId;
        this.impressionUrl = impressionUrl;
        this.clickUrl = clickUrl;
        this.adm = adm;
        this.creativeFormat = creativeFormat;
    }
}
