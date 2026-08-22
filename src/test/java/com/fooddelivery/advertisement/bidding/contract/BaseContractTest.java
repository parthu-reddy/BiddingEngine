package com.fooddelivery.advertisement.bidding.contract;

import com.fooddelivery.advertisement.bidding.controller.InternalAdController;
import com.fooddelivery.advertisement.bidding.model.internal.AdRequestDTO;
import com.fooddelivery.advertisement.bidding.model.internal.SponsoredListingDTO;
import io.restassured.module.webtestclient.RestAssuredWebTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Base class for the generated {@code fetchAds} HTTP contract test.
 *
 * <p>BiddingEngine is a WebFlux application and {@link InternalAdController#serveAds} returns
 * {@code Mono<ResponseEntity<List<SponsoredListingDTO>>>}. The contract plugin therefore runs in
 * WEBTESTCLIENT mode: under the default MOCKMVC mode the reactive return value is never dispatched
 * and every request comes back 200 with an empty body and no Content-Type.
 *
 * <p>Binding the controller directly with WebTestClient also keeps the contract hermetic -- no
 * Spring context, so no Redis, Kafka, or Eureka is required to verify the wire shape.
 */
public class BaseContractTest {

    @BeforeEach
    public void setup() {
        InternalAdController internalAdController = Mockito.mock(InternalAdController.class);

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

        RestAssuredWebTestClient.standaloneSetup(internalAdController);
    }
}
