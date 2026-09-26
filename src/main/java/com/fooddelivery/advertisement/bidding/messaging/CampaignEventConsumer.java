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

        private final com.fooddelivery.common.event.EventBinder eventBinder;

public CampaignEventConsumer(CampaignMatcher matcher, ObjectMapper objectMapper, RedisIdempotencyService redisIdempotencyService, MeterRegistry meterRegistry, com.fooddelivery.common.event.EventBinder eventBinder) {
        this.eventBinder = eventBinder;
        this.matcher = matcher;
        this.objectMapper = objectMapper;
        this.redisIdempotencyService = redisIdempotencyService;
        this.meterRegistry = meterRegistry;
    }

    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2.0), autoCreateTopics = "true", dltStrategy = DltStrategy.FAIL_ON_ERROR, exclude = {com.fooddelivery.common.event.EventBindingException.class}, traversingCauses = "true")
    @KafkaListener(topics = KafkaConstants.TOPIC_AD_EVENTS, groupId = KafkaConstants.GROUP_AD_SERVICE + "-campaigneventconsumer")
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
            // ad-events carries the campaign event type in a Kafka header and the body flat --
            // CampaignServiceImpl and PacingEngineService both write
            // objectMapper.writeValueAsString(CampaignChangedEvent), so the wire shape IS the class
            // and binding is a consumer-side change only.
            String eventTypeStr = com.fooddelivery.common.util.KafkaHeaderUtils.extractEventType(headers, null);
            if (eventTypeStr == null) {
                meterRegistry.counter("campaign_event_dropped_total", "reason", "no_event_type").increment();
                log.warn("Dropping ad-event with no eventType header");
                return;
            }
            final EventType eventType;
            try {
                eventType = EventType.valueOf(eventTypeStr);
            } catch (IllegalArgumentException e) {
                log.info("Unknown event type {} on ad-events. Ignoring.", eventTypeStr);
                return;
            }
            // Bind only what this consumer acts on. ad-events also carries AD_CREATIVE_* events,
            // whose payload is a raw AdCreative entity -- a genuinely different shape with `id`
            // rather than `campaignId`. Binding that to CampaignChangedEvent would yield an object
            // of nulls; returning first says plainly that those events are not ours.
            if (!HANDLED_EVENT_TYPES.contains(eventType)) {
                log.debug("Event {} not handled by the bidding matcher. Ignoring.", eventTypeStr);
                return;
            }

            com.fooddelivery.common.event.CampaignChangedEvent event =
                    eventBinder.bindIf(eventType, eventTypeStr, message,
                            com.fooddelivery.common.event.CampaignChangedEvent.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "bindIf returned empty for " + eventTypeStr
                                    + " despite an exact event-type match"));

            java.util.UUID campaignUuid = event.getCampaignId();
            if (campaignUuid == null) {
                meterRegistry.counter("campaign_event_dropped_total", "reason", "no_campaign_id").increment();
                log.warn("Dropping ad-event with no resolvable campaign id: {}", eventTypeStr);
                return;
            }
            String campaignId = campaignUuid.toString();

            if (eventType == EventType.AD_CAMPAIGN_PAUSED || eventType == EventType.AD_CAMPAIGN_DELETED
                    || eventType == EventType.AD_CAMPAIGN_COMPLETED) {
                log.info("Removing campaign {} from matcher due to event {}", campaignId, eventTypeStr);
                matcher.removeCampaign(campaignId);
            } else if (eventType == EventType.AD_CAMPAIGN_BUDGET_EXHAUSTED) {
                log.info("Marking campaign {} as budget exhausted in matcher", campaignId);
                matcher.updateBudgetExhausted(campaignId, true);
            } else if (eventType == EventType.AD_CAMPAIGN_PACING_UPDATED) {
                double pacingMultiplier = event.getPacingMultiplier() != null ? event.getPacingMultiplier() : 1.0;
                boolean budgetExhausted = Boolean.TRUE.equals(event.getBudgetExhausted());
                log.info("Updating pacing for campaign {} to {}, budgetExhausted={}", campaignId, pacingMultiplier, budgetExhausted);
                matcher.updatePacing(campaignId, pacingMultiplier, budgetExhausted);
            } else {
                // AD_CAMPAIGN_CREATED / RESUMED / UPDATED
                String status = event.getStatus() != null ? event.getStatus() : "ACTIVE";
                if ("ACTIVE".equals(status)) {
                    String advertiserId = event.getAdvertiserId() != null
                            ? event.getAdvertiserId().toString() : null;
                    // targeting is a TargetingSummary on the event, so the treeToValue round-trip
                    // and its try/catch are gone -- a malformed one now fails at bind time.
                    com.fooddelivery.common.dto.targeting.TargetingSummary targeting =
                            event.getSchemaVersion() != null && event.getSchemaVersion() >= 2
                                    ? event.getTargeting() : null;
                    java.util.List<String> geos = new java.util.ArrayList<>();
                    if (targeting != null && targeting.getGeoTargeting() != null
                            && targeting.getGeoTargeting().getRegions() != null) {
                        geos = new java.util.ArrayList<>(targeting.getGeoTargeting().getRegions());
                    }
                    boolean budgetExhausted = eventType == EventType.AD_CAMPAIGN_UPDATED
                            && matcher.getBudgetExhausted(campaignId);
                    double pacingMultiplier = -1.0;
                    log.info("Indexing campaign {} into matcher due to event {}", campaignId, eventTypeStr);
                    matcher.indexCampaign(campaignId, geos, advertiserId, event.getMaxBid(),
                            pacingMultiplier, budgetExhausted, targeting, event.getCreativeFormat(),
                            event.getCreativeAssetUrl(), event.getCreativeVastXml(), event.getTimeZone());
                } else {
                    log.info("Removing campaign {} from matcher due to event {} with status {}", campaignId, eventTypeStr, status);
                    matcher.removeCampaign(campaignId);
                }
            }        } catch (Exception e) {
            redisIdempotencyService.removeKey(idempotencyKeyStr);
            log.error("Failed to process campaign event, propagating for retry", e);
            throw new RuntimeException("Failed to process campaign event", e);
        }
    }

    /** The campaign events the matcher acts on. ad-events also carries AD_CREATIVE_* and others. */
    private static final java.util.Set<EventType> HANDLED_EVENT_TYPES = java.util.EnumSet.of(
            EventType.AD_CAMPAIGN_CREATED, EventType.AD_CAMPAIGN_UPDATED, EventType.AD_CAMPAIGN_RESUMED,
            EventType.AD_CAMPAIGN_PAUSED, EventType.AD_CAMPAIGN_DELETED, EventType.AD_CAMPAIGN_COMPLETED,
            EventType.AD_CAMPAIGN_BUDGET_EXHAUSTED, EventType.AD_CAMPAIGN_PACING_UPDATED);

    @DltHandler
    public void handleDlt(Object message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic, @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                          @Header(KafkaHeaders.OFFSET) long offset) {
        log.error("Campaign event sent to DLT from topic {}: {} replay={}", topic, message, com.fooddelivery.common.util.KafkaHeaderUtils.deadLetterPosition(topic, partition, offset));
        meterRegistry.counter("kafka_dlt_depth_total", "topic", topic).increment();
    }
}
