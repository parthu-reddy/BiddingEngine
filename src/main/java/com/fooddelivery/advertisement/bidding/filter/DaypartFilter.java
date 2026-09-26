package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.Instant;

@Component
@Order(4)
public class DaypartFilter implements TargetingFilter {

    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        if (campaignData.targeting == null || campaignData.targeting.getDaypartingConfig() == null || campaignData.targeting.getDaypartingConfig().getDayparts() == null || campaignData.targeting.getDaypartingConfig().getDayparts().isEmpty()) {
            return true;
        }
        
        // Dayparts are written on the advertiser's wall clock ("MONDAY 18:00-22:00"), so read that
        // clock in the advertiser's zone. It used to be one platform-wide zone for every advertiser.
        // An indexed campaign always carries its zone: without one it cannot be dayparted, so it
        // is not served rather than served at the wrong hours.
        if (campaignData.timeZone == null) {
            return false;
        }
        ZoneId zone = ZoneId.of(campaignData.timeZone);
        Instant now = Instant.now();
        String currentDayStr = com.fooddelivery.common.time.BusinessCalendar.localDate(now, zone).getDayOfWeek().name();
        int currentHour = com.fooddelivery.common.time.BusinessCalendar.localTime(now, zone).getHour();
        
        for (com.fooddelivery.common.dto.targeting.DaypartingConfig.Daypart dp : campaignData.targeting.getDaypartingConfig().getDayparts()) {
            if (dp.getDayOfWeek() != null && dp.getDayOfWeek().equalsIgnoreCase(currentDayStr)) {
                try {
                    int start = Integer.parseInt(dp.getStartTime().split(":")[0]);
                    int end = Integer.parseInt(dp.getEndTime().split(":")[0]);
                    if (currentHour >= start && currentHour < end) {
                        return true;
                    }
                } catch (Exception e) {}
            }
        }
        return false;
    }
}
