package com.fooddelivery.advertisement.bidding.model;
import java.util.List;
public class BidResponse {
    public String id;
    public String bidid;
    public List<SeatBid> seatbid;
    public BidResponse(String id, List<SeatBid> seatbid) { this.id = id; this.seatbid = seatbid; }
}