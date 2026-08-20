package com.fooddelivery.advertisement.bidding.contract;

import com.fooddelivery.advertisement.bidding.controller.InternalAdController;
import com.fooddelivery.advertisement.bidding.model.internal.AdRequestDTO;
import com.fooddelivery.advertisement.bidding.model.internal.SponsoredListingDTO;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = com.fooddelivery.advertisement.bidding.BiddingEngineApplication.class)
public class BaseContractTest {

    @MockBean
    private InternalAdController internalAdController;

    @BeforeEach
    public void setup() {
        RestAssuredMockMvc.standaloneSetup(internalAdController);

        SponsoredListingDTO mockListing = new SponsoredListingDTO(
            "AD-12345-campaign-1",
            "campaign-1",
            "http://impression",
            "http://click",
            "http://cdn/sponsored.jpg",
            "BANNER"
        );

        when(internalAdController.serveAds(any(AdRequestDTO.class)))
            .thenReturn(Mono.just(ResponseEntity.ok(List.of(mockListing))));
    }
}
