package com.fooddelivery.advertisement.bidding.matcher;

import org.roaringbitmap.RoaringBitmap;
import org.springframework.stereotype.Component;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class CampaignMatcher {

    private final Map<String, Integer> campaignToIdMap = new HashMap<>();
    private final Map<Integer, String> idToCampaignMap = new HashMap<>();
    private final Map<String, CampaignIndexData> campaignDataMap = new HashMap<>();
    private final Map<String, RoaringBitmap> geoIndex = new HashMap<>();
    private final AtomicInteger sequenceGenerator = new AtomicInteger(1);
    private final java.util.Queue<Integer> freeList = new java.util.concurrent.ConcurrentLinkedQueue<>();
    
    private final java.util.concurrent.locks.ReadWriteLock lock = new java.util.concurrent.locks.ReentrantReadWriteLock();

    public void indexCampaign(String campaignId, List<String> geos, String advertiserId, java.math.BigDecimal maxBid, double pacingMultiplier, boolean budgetExhausted, com.fooddelivery.common.dto.targeting.TargetingSummary targeting, String creativeFormat, String creativeAssetUrl, String creativeVastXml) {
        lock.writeLock().lock();
        try {
            int internalId = campaignToIdMap.computeIfAbsent(campaignId, k -> {
                Integer freeId = freeList.poll();
                int newId = (freeId != null) ? freeId : sequenceGenerator.getAndIncrement();
                idToCampaignMap.put(newId, k);
                return newId;
            });
            
            // Remove from old geos if they exist (clean up)
            for (RoaringBitmap bm : geoIndex.values()) {
                bm.remove(internalId);
            }
            
            if (geos != null && !geos.isEmpty()) {
                for (String geo : geos) {
                    geoIndex.computeIfAbsent(geo, k -> new RoaringBitmap()).add(internalId);
                }
            } else {
                geoIndex.computeIfAbsent("DEFAULT_GEO", k -> new RoaringBitmap()).add(internalId);
            }
            
            CampaignIndexData oldData = campaignDataMap.get(campaignId);
            String effAdvertiserId = advertiserId != null ? advertiserId : (oldData != null ? oldData.advertiserId : null);
            java.math.BigDecimal effMaxBid = maxBid != null ? maxBid : (oldData != null ? oldData.maxBid : null);
            double effPacing = pacingMultiplier >= 0 ? pacingMultiplier : (oldData != null ? oldData.pacingMultiplier : 1.0);
            com.fooddelivery.common.dto.targeting.TargetingSummary effTargeting = targeting != null ? targeting : (oldData != null ? oldData.targeting : null);
            String effCreativeFormat = creativeFormat != null ? creativeFormat : (oldData != null ? oldData.creativeFormat : null);
            String effCreativeAssetUrl = creativeAssetUrl != null ? creativeAssetUrl : (oldData != null ? oldData.creativeAssetUrl : null);
            String effCreativeVastXml = creativeVastXml != null ? creativeVastXml : (oldData != null ? oldData.creativeVastXml : null);
            
            campaignDataMap.put(campaignId, new CampaignIndexData(campaignId, effAdvertiserId, effMaxBid, effPacing, budgetExhausted, effTargeting, effCreativeFormat, effCreativeAssetUrl, effCreativeVastXml));
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void updateBudgetExhausted(String campaignId, boolean exhausted) {
        lock.writeLock().lock();
        try {
            CampaignIndexData oldData = campaignDataMap.get(campaignId);
            if (oldData != null) {
                campaignDataMap.put(campaignId, new CampaignIndexData(campaignId, oldData.advertiserId, oldData.maxBid, oldData.pacingMultiplier, exhausted, oldData.targeting, oldData.creativeFormat, oldData.creativeAssetUrl, oldData.creativeVastXml));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void updatePacing(String campaignId, double pacing, boolean budgetExhausted) {
        lock.writeLock().lock();
        try {
            CampaignIndexData oldData = campaignDataMap.get(campaignId);
            if (oldData != null) {
                campaignDataMap.put(campaignId, new CampaignIndexData(campaignId, oldData.advertiserId, oldData.maxBid, pacing, budgetExhausted, oldData.targeting, oldData.creativeFormat, oldData.creativeAssetUrl, oldData.creativeVastXml));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public boolean getBudgetExhausted(String campaignId) {
        lock.readLock().lock();
        try {
            CampaignIndexData data = campaignDataMap.get(campaignId);
            return data != null && data.budgetExhausted;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void removeCampaign(String campaignId) {
        lock.writeLock().lock();
        try {
            Integer internalId = campaignToIdMap.remove(campaignId);
            if (internalId != null) {
                idToCampaignMap.remove(internalId);
                for (RoaringBitmap bm : geoIndex.values()) {
                    bm.remove(internalId);
                }
                freeList.offer(internalId);
            }
            campaignDataMap.remove(campaignId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getAdvertiserId(String campaignId) {
        lock.readLock().lock();
        try {
            CampaignIndexData data = campaignDataMap.get(campaignId);
            return data != null ? data.advertiserId : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<CampaignIndexData> match(String geo) {
        List<CampaignIndexData> results = new ArrayList<>();
        lock.readLock().lock();
        try {
            RoaringBitmap matches = geoIndex.get(geo);
            if (matches == null) return results;

            org.roaringbitmap.IntIterator it = matches.getIntIterator();
            while (it.hasNext()) {
                String campaignId = idToCampaignMap.get(it.next());
                if (campaignId != null) {
                    CampaignIndexData data = campaignDataMap.get(campaignId);
                    if (data != null) {
                        results.add(data);
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return results;
    }
}

