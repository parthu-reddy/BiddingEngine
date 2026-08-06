package com.fooddelivery.advertisement.bidding.controller;

import com.google.openrtb.OpenRtb;
import com.google.protobuf.util.JsonFormat;
import com.fooddelivery.advertisement.bidding.model.BidRequest;
import com.fooddelivery.advertisement.bidding.model.Imp;
import com.fooddelivery.advertisement.bidding.model.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class OpenRtbRequestParser {

    /**
     * Parses the Google OpenRTB Protobuf object into our internal BidRequest model.
     * Extracts only the fields necessary for the RTB engine.
     */
    public BidRequest parse(OpenRtb.BidRequest protoRequest) {
        BidRequest request = new BidRequest();
        request.id = protoRequest.getId();
        request.tmax = (long) protoRequest.getTmax();
        if (protoRequest.hasAt()) {
            request.at = protoRequest.getAt().getNumber();
        }

        request.imp = new ArrayList<>();
        for (OpenRtb.BidRequest.Imp protoImp : protoRequest.getImpList()) {
            Imp imp = new Imp();
            imp.id = protoImp.getId();
            imp.bidfloor = protoImp.hasBidfloor() ? protoImp.getBidfloor() : 0.0;
            request.imp.add(imp);
        }

        if (protoRequest.hasUser()) {
            User user = new User();
            user.id = protoRequest.getUser().getId();
            if (protoRequest.getUser().hasGeo()) {
                user.geo = protoRequest.getUser().getGeo().getCountry(); 
            }
            request.user = user;
        }

        return request;
    }

    /**
     * Fallback to parse from raw JSON using Protobuf JsonFormat.
     */
    public BidRequest parseJson(String jsonPayload) throws Exception {
        OpenRtb.BidRequest.Builder builder = OpenRtb.BidRequest.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(jsonPayload, builder);
        return parse(builder.build());
    }
}
