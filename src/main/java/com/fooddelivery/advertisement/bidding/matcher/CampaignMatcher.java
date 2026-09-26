package com.fooddelivery.advertisement.bidding.matcher;

import org.roaringbitmap.RoaringBitmap;
import org.springframework.stereotype.Component;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class CampaignMatcher {

    private static class IndexSnapshot {
        final Map<String, Integer> campaignToIdMap;
        final Map<Integer, String> idToCampaignMap;
        final Map<String, CampaignIndexData> campaignDataMap;
        final Map<String, RoaringBitmap> geoIndex;

        IndexSnapshot(Map<String, Integer> cToI, Map<Integer, String> iToC, Map<String, CampaignIndexData> cData, Map<String, RoaringBitmap> gIndex) {
            this.campaignToIdMap = Map.copyOf(cToI);
            this.idToCampaignMap = Map.copyOf(iToC);
            this.campaignDataMap = Map.copyOf(cData);
            
            Map<String, RoaringBitmap> copiedGeo = new HashMap<>();
            for (Map.Entry<String, RoaringBitmap> entry : gIndex.entrySet()) {
                copiedGeo.put(entry.getKey(), entry.getValue().clone());
            }
            this.geoIndex = Map.copyOf(copiedGeo);
        }
    }

    private final AtomicReference<IndexSnapshot> snapshot = new AtomicReference<>(
            new IndexSnapshot(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>())
    );

    private final Lock writeLock = new ReentrantLock();
    private int sequenceGenerator = 1;
    private final java.util.Queue<Integer> freeList = new java.util.LinkedList<>();

    public void indexCampaign(String campaignId, List<String> geos, String advertiserId, java.math.BigDecimal maxBid, double pacingMultiplier, boolean budgetExhausted, com.fooddelivery.common.dto.targeting.TargetingSummary targeting, String creativeFormat, String creativeAssetUrl, String creativeVastXml, String timeZone) {
        writeLock.lock();
        try {
            IndexSnapshot current = snapshot.get();
            Map<String, Integer> cToI = new HashMap<>(current.campaignToIdMap);
            Map<Integer, String> iToC = new HashMap<>(current.idToCampaignMap);
            Map<String, CampaignIndexData> cData = new HashMap<>(current.campaignDataMap);
            Map<String, RoaringBitmap> gIndex = new HashMap<>();
            for (Map.Entry<String, RoaringBitmap> entry : current.geoIndex.entrySet()) {
                gIndex.put(entry.getKey(), entry.getValue().clone());
            }

            int internalId;
            if (cToI.containsKey(campaignId)) {
                internalId = cToI.get(campaignId);
            } else {
                Integer freeId = freeList.poll();
                internalId = (freeId != null) ? freeId : sequenceGenerator++;
                cToI.put(campaignId, internalId);
                iToC.put(internalId, campaignId);
            }

            for (RoaringBitmap bm : gIndex.values()) {
                bm.remove(internalId);
            }

            if (geos != null && !geos.isEmpty()) {
                for (String geo : geos) {
                    gIndex.computeIfAbsent(geo, k -> new RoaringBitmap()).add(internalId);
                }
            } else {
                gIndex.computeIfAbsent("DEFAULT_GEO", k -> new RoaringBitmap()).add(internalId);
            }
            
            CampaignIndexData oldData = cData.get(campaignId);
            String effAdvertiserId = advertiserId != null ? advertiserId : (oldData != null ? oldData.advertiserId : null);
            java.math.BigDecimal effMaxBid = maxBid != null ? maxBid : (oldData != null ? oldData.maxBid : null);
            double effPacing = pacingMultiplier >= 0 ? pacingMultiplier : (oldData != null ? oldData.pacingMultiplier : 1.0);
            com.fooddelivery.common.dto.targeting.TargetingSummary effTargeting = targeting != null ? targeting : (oldData != null ? oldData.targeting : null);
            String effCreativeFormat = creativeFormat != null ? creativeFormat : (oldData != null ? oldData.creativeFormat : null);
            String effCreativeAssetUrl = creativeAssetUrl != null ? creativeAssetUrl : (oldData != null ? oldData.creativeAssetUrl : null);
            String effCreativeVastXml = creativeVastXml != null ? creativeVastXml : (oldData != null ? oldData.creativeVastXml : null);
            String effTimeZone = timeZone != null ? timeZone : (oldData != null ? oldData.timeZone : null);
            
            cData.put(campaignId, new CampaignIndexData(campaignId, effAdvertiserId, effMaxBid, effPacing, budgetExhausted, effTargeting, effCreativeFormat, effCreativeAssetUrl, effCreativeVastXml, effTimeZone));

            snapshot.set(new IndexSnapshot(cToI, iToC, cData, gIndex));
        } finally {
            writeLock.unlock();
        }
    }
    
    public void updateBudgetExhausted(String campaignId, boolean exhausted) {
        writeLock.lock();
        try {
            IndexSnapshot current = snapshot.get();
            CampaignIndexData oldData = current.campaignDataMap.get(campaignId);
            if (oldData != null) {
                Map<String, CampaignIndexData> cData = new HashMap<>(current.campaignDataMap);
                cData.put(campaignId, new CampaignIndexData(campaignId, oldData.advertiserId, oldData.maxBid, oldData.pacingMultiplier, exhausted, oldData.targeting, oldData.creativeFormat, oldData.creativeAssetUrl, oldData.creativeVastXml, oldData.timeZone));
                snapshot.set(new IndexSnapshot(current.campaignToIdMap, current.idToCampaignMap, cData, current.geoIndex));
            }
        } finally {
            writeLock.unlock();
        }
    }
    
    public void updatePacing(String campaignId, double pacing, boolean budgetExhausted) {
        writeLock.lock();
        try {
            IndexSnapshot current = snapshot.get();
            CampaignIndexData oldData = current.campaignDataMap.get(campaignId);
            if (oldData != null) {
                Map<String, CampaignIndexData> cData = new HashMap<>(current.campaignDataMap);
                cData.put(campaignId, new CampaignIndexData(campaignId, oldData.advertiserId, oldData.maxBid, pacing, budgetExhausted, oldData.targeting, oldData.creativeFormat, oldData.creativeAssetUrl, oldData.creativeVastXml, oldData.timeZone));
                snapshot.set(new IndexSnapshot(current.campaignToIdMap, current.idToCampaignMap, cData, current.geoIndex));
            }
        } finally {
            writeLock.unlock();
        }
    }
    
    public boolean getBudgetExhausted(String campaignId) {
        CampaignIndexData data = snapshot.get().campaignDataMap.get(campaignId);
        return data != null && data.budgetExhausted;
    }

    public void removeCampaign(String campaignId) {
        writeLock.lock();
        try {
            IndexSnapshot current = snapshot.get();
            Map<String, Integer> cToI = new HashMap<>(current.campaignToIdMap);
            Map<Integer, String> iToC = new HashMap<>(current.idToCampaignMap);
            Map<String, CampaignIndexData> cData = new HashMap<>(current.campaignDataMap);
            Map<String, RoaringBitmap> gIndex = new HashMap<>();
            for (Map.Entry<String, RoaringBitmap> entry : current.geoIndex.entrySet()) {
                gIndex.put(entry.getKey(), entry.getValue().clone());
            }

            Integer internalId = cToI.remove(campaignId);
            if (internalId != null) {
                iToC.remove(internalId);
                for (RoaringBitmap bm : gIndex.values()) {
                    bm.remove(internalId);
                }
                freeList.offer(internalId);
            }
            cData.remove(campaignId);
            snapshot.set(new IndexSnapshot(cToI, iToC, cData, gIndex));
        } finally {
            writeLock.unlock();
        }
    }

    public String getAdvertiserId(String campaignId) {
        CampaignIndexData data = snapshot.get().campaignDataMap.get(campaignId);
        return data != null ? data.advertiserId : null;
    }

    public List<CampaignIndexData> match(String geo) {
        List<CampaignIndexData> results = new ArrayList<>();
        IndexSnapshot current = snapshot.get();
        RoaringBitmap matches = current.geoIndex.get(geo);
        if (matches == null) return results;

        org.roaringbitmap.IntIterator it = matches.getIntIterator();
        while (it.hasNext()) {
            String campaignId = current.idToCampaignMap.get(it.next());
            if (campaignId != null) {
                CampaignIndexData data = current.campaignDataMap.get(campaignId);
                if (data != null) {
                    results.add(data);
                }
            }
        }
        return results;
    }

    public int getIndexedCampaignsCount() {
        return snapshot.get().campaignDataMap.size();
    }

    public List<String> getAllIndexedCampaignIds() {
        return new ArrayList<>(snapshot.get().campaignDataMap.keySet());
    }
}
