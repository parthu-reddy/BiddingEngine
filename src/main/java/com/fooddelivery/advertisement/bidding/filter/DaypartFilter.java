package com.fooddelivery.advertisement.bidding.filter;

import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
@Order(4)
public class DaypartFilter implements TargetingFilter {

    @Value("${platform.business-zone:UTC}")
    private String businessZone;

    @Override
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        if (campaignData.targeting == null || campaignData.targeting.getDaypartingConfig() == null || campaignData.targeting.getDaypartingConfig().getDayparts() == null || campaignData.targeting.getDaypartingConfig().getDayparts().isEmpty()) {
            return true;
        }
        
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(businessZone)); 
        String currentDayStr = now.getDayOfWeek().name();
        int currentHour = now.getHour();
        
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
