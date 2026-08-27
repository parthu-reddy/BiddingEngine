package com.fooddelivery.advertisement.bidding.model;
import java.util.List;
public class BidResponse {
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    public String id;
    public String bidid;
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    public List<SeatBid> seatbid;
    public BidResponse(String id, List<SeatBid> seatbid) { this.id = id; this.seatbid = seatbid; }
}