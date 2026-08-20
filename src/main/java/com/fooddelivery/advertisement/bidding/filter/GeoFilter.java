package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class GeoFilter implements TargetingFilter {
    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        if (campaignData.targeting == null || campaignData.targeting.getGeoTargeting() == null || campaignData.targeting.getGeoTargeting().getLocations() == null || campaignData.targeting.getGeoTargeting().getLocations().isEmpty()) {
            return true;
        }
        // BidRequest currently only has user.geo String (e.g. "US"). 
        // Real geo filtering requires lat/lng in BidRequest to match GeoLocations.
        // We will default to true for now since real geofence logic requires user lat/lng.
        return true;
    }
}
