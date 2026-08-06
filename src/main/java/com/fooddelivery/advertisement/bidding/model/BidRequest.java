package com.fooddelivery.advertisement.bidding.model;
import java.util.List;
public class BidRequest {
    public String id;
    public List<Imp> imp;
    public User user;
    public Integer at = 2;
    public Long tmax;
}