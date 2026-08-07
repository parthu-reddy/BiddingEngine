package com.fooddelivery.advertisement.bidding.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.constants.KafkaConstants;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class CampaignEventConsumer {
    @java.lang.SuppressWarnings("all")
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CampaignEventConsumer.class);
    private final CampaignMatcher matcher;
    private final ObjectMapper objectMapper;

    public CampaignEventConsumer(CampaignMatcher matcher, ObjectMapper objectMapper) {
        this.matcher = matcher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaConstants.TOPIC_AD_EVENTS, groupId = KafkaConstants.GROUP_AD_SERVICE)
    public void consumeCampaignEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root.has("eventType") && root.has("payload")) {
                String eventTypeStr = root.get("eventType").asText();
                JsonNode payload = root.get("payload");
                // Sometimes outbox payload is stringified JSON
                if (payload.isTextual()) {
                    payload = objectMapper.readTree(payload.asText());
                }
                String campaignId = payload.has("id") ? payload.get("id").asText() : null;
                if (campaignId == null) {
                    return;
                }
                if (EventType.AD_CAMPAIGN_PAUSED.name().equals(eventTypeStr) || EventType.AD_CAMPAIGN_DELETED.name().equals(eventTypeStr) || EventType.AD_CAMPAIGN_BUDGET_EXHAUSTED.name().equals(eventTypeStr)) {
                    log.info("Removing campaign {} from matcher due to event {}", campaignId, eventTypeStr);
                    matcher.removeCampaign(campaignId);
                } else if (EventType.AD_CAMPAIGN_CREATED.name().equals(eventTypeStr) || EventType.AD_CAMPAIGN_RESUMED.name().equals(eventTypeStr) || EventType.AD_CAMPAIGN_UPDATED.name().equals(eventTypeStr)) {
                    String advertiserId = payload.has("advertiserId") ? payload.get("advertiserId").asText() : null;
                    String geo = BiddingConstants.DEFAULT_GEO;
                    log.info("Indexing campaign {} into matcher due to event {}", campaignId, eventTypeStr);
                    matcher.indexCampaign(campaignId, geo, advertiserId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process campaign event", e);
        }
    }
}
