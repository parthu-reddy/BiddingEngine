package com.fooddelivery.advertisement.bidding.constants;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fooddelivery.common.constants.AdMacroConstants;

@Component
public class TrackingUrlConstants {

    @Value("${bidding.tracking.impression.url:https://event-tracking-service/api/v1/tracking/impression?campaignId=%s&advertiserId=%s&wp=%s}")
    public String impressionTrackingUrlTemplate;

    @Value("${bidding.tracking.click.url:https://event-tracking-service/api/v1/tracking/click?campaignId=%s&advertiserId=%s}")
    public String clickTrackingUrlTemplate;

    @Value("${bidding.tracking.cdn.url:https://cdn.example.com/sponsored-%s.png}")
    public String cdnSponsoredImageTemplate;
}
