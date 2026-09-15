package com.fooddelivery.advertisement.bidding.model.internal;

public class SponsoredListingDTO {
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    public String adId;
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    public String campaignId;
    public String impressionUrl;
    public String clickUrl;
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    public String adm;
    public String creativeFormat;
    public String advertiserId;
    
    public SponsoredListingDTO(String adId, String campaignId, String impressionUrl, String clickUrl, String adm, String creativeFormat, String advertiserId) {
        this.adId = adId;
        this.campaignId = campaignId;
        this.impressionUrl = impressionUrl;
        this.clickUrl = clickUrl;
        this.adm = adm;
        this.creativeFormat = creativeFormat;
        this.advertiserId = advertiserId;
    }
}
