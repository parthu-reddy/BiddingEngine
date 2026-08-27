package com.fooddelivery.advertisement.bidding.model;
import java.util.List;
public class SeatBid {
    @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    public List<Bid> bid;
    public SeatBid(List<Bid> bid) { this.bid = bid; }
}