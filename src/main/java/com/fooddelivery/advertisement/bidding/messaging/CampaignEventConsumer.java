package com.fooddelivery.advertisement.bidding.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.advertisement.bidding.matcher.CampaignMatcher;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.constants.KafkaConstants;
import com.fooddelivery.advertisement.bidding.constants.BiddingConstants;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.UUID;

@Service
@lombok.extern.slf4j.Slf4j
public class CampaignEventConsumer {
    @java.lang.SuppressWarnings("all")

    private final CampaignMatcher matcher;
    private final ObjectMapper objectMapper;
    private final RedisIdempotencyService redisIdempotencyService;
    private final MeterRegistry meterRegistry;

    public CampaignEventConsumer(CampaignMatcher matcher, ObjectMapper objectMapper, RedisIdempotencyService redisIdempotencyService, MeterRegistry meterRegistry) {
        this.matcher = matcher;
        this.objectMapper = objectMapper;
        this.redisIdempotencyService = redisIdempotencyService;
        this.meterRegistry = meterRegistry;
    }

    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2.0), autoCreateTopics = "true", dltStrategy = DltStrategy.FAIL_ON_ERROR)
    @KafkaListener(topics = KafkaConstants.TOPIC_AD_EVENTS, groupId = KafkaConstants.GROUP_AD_SERVICE)
    public void consumeCampaignEvent(String message, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        
        String extractedEventId = com.fooddelivery.common.util.KafkaHeaderUtils.extractHeaderValue(headers, "eventId");
        final String resolvedEventId;
        if (extractedEventId == null) {
            resolvedEventId = UUID.nameUUIDFromBytes(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        } else {
            resolvedEventId = extractedEventId;
        }

        String idempotencyKeyStr = "processed_event:bidding:" + resolvedEventId;

        if (redisIdempotencyService.isDuplicate(idempotencyKeyStr)) {
            log.info("Duplicate campaign event ignored: {}", idempotencyKeyStr);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(message);
            // CampaignService publishes the saved Campaign FLAT, with the event type carried only
            // as a Kafka header. The previous guard required an {eventType, payload} envelope and
            // was therefore never true, so campaigns were never indexed into or removed from the
            // matcher -- paused and deleted campaigns kept serving.
            String eventTypeStr = com.fooddelivery.common.util.EventPayloadUtils.resolveEventType(root, headers);
            JsonNode payload = com.fooddelivery.common.util.EventPayloadUtils.unwrapPayload(root);
            if (eventTypeStr != null && payload != null) {
                String campaignId = com.fooddelivery.common.util.EventPayloadUtils.campaignId(payload);
                if (campaignId == null) {
                    meterRegistry.counter("campaign_event_dropped_total", "reason", "no_campaign_id").increment();
                    log.warn("Dropping ad-event with no resolvable campaign id: {}", eventTypeStr);
                    return;
                }
                if (EventType.AD_CAMPAIGN_PAUSED.name().equals(eventTypeStr) || EventType.AD_CAMPAIGN_DELETED.name().equals(eventTypeStr)) {
                    log.info("Removing campaign {} from matcher due to event {}", campaignId, eventTypeStr);
                    matcher.removeCampaign(campaignId);
                } else if (EventType.AD_CAMPAIGN_BUDGET_EXHAUSTED.name().equals(eventTypeStr)) {
                    log.info("Marking campaign {} as budget exhausted in matcher", campaignId);
                    matcher.updateBudgetExhausted(campaignId, true);
                } else if (EventType.AD_CAMPAIGN_PACING_UPDATED.name().equals(eventTypeStr)) {
                    double pacingMultiplier = payload.has("pacingMultiplier") ? payload.get("pacingMultiplier").asDouble(1.0) : 1.0;
                    boolean budgetExhausted = payload.has("budgetExhausted") && payload.get("budgetExhausted").asBoolean();
                    log.info("Updating pacing for campaign {} to {}, budgetExhausted={}", campaignId, pacingMultiplier, budgetExhausted);
                    matcher.updatePacing(campaignId, pacingMultiplier, budgetExhausted);
                } else if (EventType.AD_CAMPAIGN_CREATED.name().equals(eventTypeStr) || EventType.AD_CAMPAIGN_RESUMED.name().equals(eventTypeStr) || EventType.AD_CAMPAIGN_UPDATED.name().equals(eventTypeStr)) {
                    String status = payload.has("status") ? payload.get("status").asText() : "ACTIVE";
                    
                    if ("ACTIVE".equals(status)) {
                        String advertiserId = payload.has("advertiserId") ? payload.get("advertiserId").asText() : null;
                        com.fooddelivery.common.dto.targeting.TargetingSummary targeting = null;
                        java.util.List<String> geos = new java.util.ArrayList<>();
                        
                        if (payload.has("schemaVersion") && payload.get("schemaVersion").asInt() >= 2 && payload.has("targeting")) {
                            try {
                                targeting = objectMapper.treeToValue(payload.get("targeting"), com.fooddelivery.common.dto.targeting.TargetingSummary.class);
                                if (targeting != null && targeting.getGeoTargeting() != null && targeting.getGeoTargeting().getLocations() != null && !targeting.getGeoTargeting().getLocations().isEmpty()) {
                                    geos = java.util.List.of("GLOBAL"); // Fallback for indexer, actual geo handled by filter
                                }
                            } catch (Exception e) {
                                log.warn("Failed to parse targeting summary for campaign {}", campaignId, e);
                            }
                        }
                        
                        if (geos.isEmpty()) {
                            geos.add(BiddingConstants.DEFAULT_GEO);
                        }
                        
                        java.math.BigDecimal maxBid = null;
                        if (payload.has("maxBid")) {
                            try {
                                maxBid = new java.math.BigDecimal(payload.get("maxBid").asText());
                            } catch (Exception ignored) {}
                        }
                        
                        boolean budgetExhausted = false;
                        if (EventType.AD_CAMPAIGN_UPDATED.name().equals(eventTypeStr)) {
                            budgetExhausted = matcher.getBudgetExhausted(campaignId);
                        }
                        
                        double pacingMultiplier = -1.0;
                        
                        String creativeFormat = payload.has("creativeFormat") && !payload.get("creativeFormat").isNull() ? payload.get("creativeFormat").asText() : null;
                        String creativeAssetUrl = payload.has("creativeAssetUrl") && !payload.get("creativeAssetUrl").isNull() ? payload.get("creativeAssetUrl").asText() : null;
                        String creativeVastXml = payload.has("creativeVastXml") && !payload.get("creativeVastXml").isNull() ? payload.get("creativeVastXml").asText() : null;
                        
                        log.info("Indexing campaign {} into matcher due to event {}", campaignId, eventTypeStr);
                        matcher.indexCampaign(campaignId, geos, advertiserId, maxBid, pacingMultiplier, budgetExhausted, targeting, creativeFormat, creativeAssetUrl, creativeVastXml);
                    } else {
                        log.info("Removing campaign {} from matcher due to event {} with status {}", campaignId, eventTypeStr, status);
                        matcher.removeCampaign(campaignId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process campaign event, propagating for retry", e);
            throw new RuntimeException("Failed to process campaign event", e);
        }
    }

    @DltHandler
    public void handleDlt(Object message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Campaign event failed all retries and sent to DLT: {} - {}", topic, message);
    }
}
