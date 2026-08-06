package com.fooddelivery.advertisement.bidding.constants;

public final class TrackingUrlConstants {
    private TrackingUrlConstants() {}

    public static final String IMPRESSION_TRACKING_URL_TEMPLATE = "http://event-tracking-service/api/v1/tracking/impression?campaignId=%s&advertiserId=adv-1&wp=" + com.fooddelivery.common.constants.AdMacroConstants.MACRO_AUCTION_PRICE;
    public static final String CLICK_TRACKING_URL_TEMPLATE = "http://event-tracking-service/api/v1/tracking/click?campaignId=%s&advertiserId=adv-1";
    public static final String CDN_SPONSORED_IMAGE_TEMPLATE = "http://cdn.com/sponsored-%s.png";
}
