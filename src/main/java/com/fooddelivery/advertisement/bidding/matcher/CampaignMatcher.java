package com.fooddelivery.advertisement.bidding.matcher;

import org.roaringbitmap.RoaringBitmap;
import org.springframework.stereotype.Component;
import com.fooddelivery.advertisement.bidding.model.CampaignIndexData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class CampaignMatcher {

    private final AtomicInteger sequenceGenerator = new AtomicInteger(1);
    
    // Immutable snapshot class for RCU
    private static class IndexSnapshot {
        final Map<String, Integer> campaignToIdMap;
        final Map<Integer, String> idToCampaignMap;
        final Map<String, String> campaignToAdvertiserMap;
        final Map<String, RoaringBitmap> geoIndex;

        IndexSnapshot() {
            this.campaignToIdMap = Map.of();
            this.idToCampaignMap = Map.of();
            this.campaignToAdvertiserMap = Map.of();
            this.geoIndex = Map.of();
        }

        IndexSnapshot(Map<String, Integer> campaignToIdMap, 
                      Map<Integer, String> idToCampaignMap, 
                      Map<String, String> campaignToAdvertiserMap, 
                      Map<String, RoaringBitmap> geoIndex) {
            this.campaignToIdMap = Map.copyOf(campaignToIdMap);
            this.idToCampaignMap = Map.copyOf(idToCampaignMap);
            this.campaignToAdvertiserMap = Map.copyOf(campaignToAdvertiserMap);
            this.geoIndex = Map.copyOf(geoIndex);
        }
    }

    // Volatile reference to the current immutable snapshot
    private volatile IndexSnapshot currentSnapshot = new IndexSnapshot();

    // Single write lock to serialize updates, readers are NEVER blocked
    private final Object writeLock = new Object();

    public void indexCampaign(String campaignId, String geo, String advertiserId) {
        synchronized(writeLock) {
            IndexSnapshot oldSnapshot = currentSnapshot;
            
            // Create mutable copies for the update
            Map<String, Integer> newCampaignToIdMap = new ConcurrentHashMap<>(oldSnapshot.campaignToIdMap);
            Map<Integer, String> newIdToCampaignMap = new ConcurrentHashMap<>(oldSnapshot.idToCampaignMap);
            Map<String, String> newCampaignToAdvertiserMap = new ConcurrentHashMap<>(oldSnapshot.campaignToAdvertiserMap);
            Map<String, RoaringBitmap> newGeoIndex = new ConcurrentHashMap<>();
            
            // Deep copy bitmaps
            oldSnapshot.geoIndex.forEach((k, v) -> newGeoIndex.put(k, v.clone()));

            int internalId = newCampaignToIdMap.computeIfAbsent(campaignId, k -> {
                int newId = sequenceGenerator.getAndIncrement();
                newIdToCampaignMap.put(newId, k);
                return newId;
            });
            
            newGeoIndex.computeIfAbsent(geo, k -> new RoaringBitmap()).add(internalId);
            
            if (advertiserId != null) {
                newCampaignToAdvertiserMap.put(campaignId, advertiserId);
            }
            
            // Atomic volatile write of the new snapshot
            currentSnapshot = new IndexSnapshot(newCampaignToIdMap, newIdToCampaignMap, newCampaignToAdvertiserMap, newGeoIndex);
        }
    }

    public void indexCampaign(String campaignId, String geo) {
        indexCampaign(campaignId, geo, null);
    }

    public void removeCampaign(String campaignId) {
        synchronized(writeLock) {
            IndexSnapshot oldSnapshot = currentSnapshot;
            
            if (!oldSnapshot.campaignToIdMap.containsKey(campaignId)) return;

            Map<String, Integer> newCampaignToIdMap = new ConcurrentHashMap<>(oldSnapshot.campaignToIdMap);
            Map<Integer, String> newIdToCampaignMap = new ConcurrentHashMap<>(oldSnapshot.idToCampaignMap);
            Map<String, String> newCampaignToAdvertiserMap = new ConcurrentHashMap<>(oldSnapshot.campaignToAdvertiserMap);
            Map<String, RoaringBitmap> newGeoIndex = new ConcurrentHashMap<>();
            
            oldSnapshot.geoIndex.forEach((k, v) -> newGeoIndex.put(k, v.clone()));

            Integer internalId = newCampaignToIdMap.remove(campaignId);
            if (internalId != null) {
                newIdToCampaignMap.remove(internalId);
                for (RoaringBitmap bm : newGeoIndex.values()) {
                    bm.remove(internalId);
                }
            }
            newCampaignToAdvertiserMap.remove(campaignId);
            
            currentSnapshot = new IndexSnapshot(newCampaignToIdMap, newIdToCampaignMap, newCampaignToAdvertiserMap, newGeoIndex);
        }
    }

    public String getAdvertiserId(String campaignId) {
        return currentSnapshot.campaignToAdvertiserMap.get(campaignId); // Lock-free
    }

    public List<CampaignIndexData> match(String geo) {
        // Lock-free read!
        IndexSnapshot snapshot = currentSnapshot;
        RoaringBitmap matches = snapshot.geoIndex.get(geo);
        
        List<CampaignIndexData> results = new ArrayList<>();
        if (matches == null) return results;

        org.roaringbitmap.IntIterator it = matches.getIntIterator();
        while (it.hasNext()) {
            String campaignId = snapshot.idToCampaignMap.get(it.next());
            if (campaignId != null) {
                String advertiserId = snapshot.campaignToAdvertiserMap.get(campaignId);
                results.add(new CampaignIndexData(campaignId, advertiserId));
            }
        }
        return results;
    }
}

