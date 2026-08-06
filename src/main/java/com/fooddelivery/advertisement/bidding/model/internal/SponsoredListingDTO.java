package com.fooddelivery.advertisement.bidding.model.internal;

public class SponsoredListingDTO {
    public String adId;
    public String campaignId;
    public String impressionUrl;
    public String clickUrl;
    public String imageUrl;
    
    public SponsoredListingDTO(String adId, String campaignId, String impressionUrl, String clickUrl, String imageUrl) {
        this.adId = adId;
        this.campaignId = campaignId;
        this.impressionUrl = impressionUrl;
        this.clickUrl = clickUrl;
        this.imageUrl = imageUrl;
    }
}
