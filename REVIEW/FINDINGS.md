# Findings

Each entry gives the evidence, the mechanism, and the fix. Line references are from 2026-08-20.

---

## C1 — Redis configuration is nested under `outbox:`, not `spring:` {#c1}

**`src/main/resources/application.yml`:**

```yaml
outbox:
  enabled: false
  data:              # ← these two lines belong under `spring:`
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

Parsed, the tree is `outbox.data.redis.host`. Spring never reads it, so `REDIS_HOST` and `REDIS_PORT`
are **ignored** and `ReactiveStringRedisTemplate` falls back to `localhost:6379`.

**Effect.** In any environment where Redis is not on localhost — Docker Compose, Kubernetes, staging —
every `cacheProvider.get(...)` fails or returns empty. `DisruptorService` then hits:

```java
if (maxBidStr.isEmpty()) {
    log.warn("No maxBid found in cache for campaign {}. Refusing to bid.", selectedCampaign);
    sink.success(ResponseEntity.noContent().build());
```

so the service returns **204 No Content for every bid request**. That is indistinguishable from "no
matching campaign". The service looks healthy; it earns nothing.

**Fix.** Re-indent the two lines under `spring:`. One-line change, highest value in this document.

---

## C2 — The campaign index has no bootstrap {#c2}

`CampaignMatcher` holds the entire serving index in memory (`volatile IndexSnapshot`). It is populated
**only** by `CampaignEventConsumer` reacting to `ad-events`. There is no `@PostConstruct` load, no
`ApplicationReadyEvent` warm-up, and no query to CampaignService at startup — I searched for all three.

**Effect.** On every restart, deploy or pod reschedule the index starts **empty**. `match()` returns an
empty list, every request 204s, and the index only refills as *new* campaign events arrive. Campaigns
created before the restart are never re-indexed, because the consumer group resumes from its committed
offset rather than replaying history.

So: **a restart takes the ad platform to zero revenue, silently, until every campaign happens to be
edited.** This is the most operationally severe finding here.

**Fix.** Add a startup loader that pulls active campaigns from CampaignService (it already exposes
`getDailyBudgets`; an "active campaigns" endpoint may need adding) and calls `indexCampaign` for each
before the service reports ready. Gate readiness on it — a bidder with an empty index should fail its
readiness probe rather than serve 204s.

---

## C3 — `/ads/serve` never applies the filter chain {#c3}

`InternalAdController` injects the chain and never reads it:

```java
private final List<TargetingFilter> filterChain;                       // line 31
public InternalAdController(..., List<TargetingFilter> filterChain, ...) {
    this.filterChain = filterChain;                                    // line 37 — last mention
}
```

`serveAds` goes straight from `matcher.match(geo)` to `.take(3)` with no filtering.

**Effect.** The sponsored-listing path — the one customers actually see in the app — applies **no
budget check** (`FinancialFilter`), **no pacing**, and **no brand safety**. A campaign whose advertiser
wallet is empty keeps serving sponsored listings indefinitely. Only the RTB path filters.

Worth noting the asymmetry: RTB, the path with an external auction and a counterparty, is filtered.
The internal path, where the platform pays the cost of a bad impression, is not.

**Fix.** Apply the same chain in `serveAds`. Because the handler is reactive and the filters are
blocking, do this together with H1 — otherwise you move the blocking problem onto the WebFlux event
loop, which is worse than where it is now.

---

## C4 — Disruptor response objects are mutated in an async callback {#c4}

`DisruptorService.BiddingEvent` pre-allocates the response graph per ring-buffer slot:

```java
public final Bid preAllocatedBid;
public final SeatBid preAllocatedSeatBid;
public final BidResponse preAllocatedResponse;
```

`processBid` then does its Redis lookup **asynchronously** and mutates those objects inside the
callback:

```java
Mono.zip(pacingMono, maxBidMono).subscribe(tuple -> {
    ...
    event.preAllocatedBid.id    = UUID.randomUUID().toString();
    event.preAllocatedBid.price = bidPrice;
    ...
    sink.success(ResponseEntity.ok(event.preAllocatedResponse));
});
```

**Mechanism.** The Disruptor event handler returns as soon as `subscribe()` is called — it does not
wait for the callback. The Disruptor therefore marks the sequence processed and the **slot becomes
reusable while the callback is still pending**. Two hazards follow:

1. A later request publishes into the same slot and overwrites `event.request`/`event.sink` while the
   earlier callback still holds a reference to `event`. The earlier callback then mutates a `Bid` that
   the newer request also owns.
2. `sink.success(event.preAllocatedResponse)` hands a **mutable, slot-owned** object to Reactor. Netty
   serialises it later still. If the slot is reused before serialisation, the bid that goes out on the
   wire can carry another request's price, campaign or impression id.

In an RTB system that is billing the wrong advertiser at the wrong price.

**Window.** The ring buffer is 262,144 slots, so wrap-around needs that many publishes while a callback
is outstanding. At low traffic it will never happen; under sustained load, or whenever Redis latency
rises, it will — and it is precisely under those conditions that the buffer fills.

**The optimisation does not even pay for itself.** The same callback allocates a `UUID`, two
`String.format` results, a `BigDecimal` and a `ResponseEntity` on every bid. Pre-allocating the DTO
graph saves three small objects while introducing a data race.

**Fix.** Allocate the response in the callback and delete the pre-allocated fields. If zero-allocation
on this path is genuinely wanted, the response must be built and serialised **before** the handler
returns — which means the Redis lookup has to move off the critical path (see DESIGN.md).

---

## C5 — L1 cache serves budget data up to 10 minutes stale, and never expires it {#c5}

```java
this.l1Cache = Caffeine.newBuilder()
    .refreshAfterWrite(10, TimeUnit.MINUTES)
    .maximumSize(100_000)
    .buildAsync((key, executor) -> fallbackLoader.apply(key).toFuture());
```

There is **no `expireAfterWrite`**. `refreshAfterWrite` is stale-while-revalidate: after 10 minutes the
next access returns the **old** value and refreshes in the background. An entry that is never accessed
is never refreshed at all, and eviction happens only at 100,000 entries.

**Effect.** This cache backs `PREFIX_AD_WALLET_BALANCE` (the budget check in `FinancialFilter`) and
`PREFIX_AD_CAMPAIGN_MAX_BID`. When an advertiser exhausts their budget, `BudgetLimitingService` updates
Redis — and BiddingEngine keeps bidding on the stale balance for up to 10 minutes, then serves the
stale value once more while refreshing. **The budget-limiting service cannot actually limit spend.**

**Fix.** Budget-sensitive keys need a short `expireAfterWrite` — seconds, not minutes — or an explicit
invalidation path driven by the same Kafka events that already reach this service. Ten-minute
staleness is defensible for campaign metadata; it is not defensible for money.

---

## H1 — Blocking calls inside the Disruptor handler {#h1}

`FinancialFilter` and `PacingFilter` both do:

```java
String budgetStr = cache.get(budgetKey).block(java.time.Duration.ofMillis(50));
```

The filter chain runs inside `processBid`, on the single Disruptor handler thread, inside a
`stream().filter(...).findFirst()` — so it blocks **per candidate campaign** until one passes.

**Effect.** One bid request can hold the only Disruptor thread for 50 ms × 2 filters × candidates
evaluated. All RTB traffic is serialised behind that. When the ring buffer fills, `ringBuffer.next()`
blocks the *publisher* — which is a Netty event loop thread — and the whole reactive stack stalls.

This inverts the entire point of the architecture: the Disruptor exists to keep the critical path
non-blocking, and the filters block on network I/O in the middle of it.

**Fix.** Make the filter chain reactive (`Mono<Boolean> evaluate(...)`) and compose it, or pre-resolve
budget and pacing into the in-memory index so filters are pure CPU. The second is the better fit — see
DESIGN.md.

---

## H2 — `match()` materialises every campaign in a geo {#h2}

```java
public List<CampaignIndexData> match(String geo) {
    ...
    while (it.hasNext()) {
        results.add(new CampaignIndexData(campaignId, advertiserId));
    }
    return results;
}
```

Both callers then discard almost all of it — `serveAds` does `.take(3)`, `processBid` does
`.findFirst()`. For a geo with 50,000 campaigns, every single request allocates 50,000 objects to use
one to three.

**Fix.** Return a lazy `Stream`/`Flux`, or accept a limit and stop iterating early. The RoaringBitmap
iterator already supports this; only the eager `ArrayList` prevents it.

---

## H3 — Every index write deep-clones the whole geo index {#h3}

```java
oldSnapshot.geoIndex.forEach((k, v) -> newGeoIndex.put(k, v.clone()));
```

This runs in `indexCampaign` **and** `removeCampaign`, so one campaign event clones every bitmap for
every geo. Cost is O(total campaigns), not O(1), on each of create/update/pause/delete.

The RCU pattern is right; the copy granularity is wrong. Readers only need the *changed* geo to be
replaced atomically.

**Fix.** Copy-on-write per geo: clone only the affected bitmap and share the untouched ones by
reference. They are already effectively immutable between writes.

---

## H4 — There is no auction {#h4}

```java
Optional<CampaignIndexData> bestCampaignOpt = matchedCampaigns.stream()
        .filter(...)      // targeting
        .findFirst();     // ← "best" is whichever comes first
```

The variable is named `bestCampaignOpt`, but `findFirst()` takes the first campaign that passes
targeting. Iteration order is RoaringBitmap order, which is internal-id order, which is **insertion
order**. So the oldest eligible campaign always wins, deterministically.

Price is computed *after* selection, so a campaign willing to pay ten times more never gets the chance.

**Fix.** Score all eligible candidates and take the maximum — by bid price, or eCPM if a CTR estimate
is available. This is the difference between an ad server and a first-come-first-served rota, and it is
the single biggest revenue item in this document.

---

## M1 — `new BigDecimal(double)` on the price path {#m1}

```java
BigDecimal shaded = maxBid.multiply(new BigDecimal(pacingS)).multiply(BiddingConstants.SHADING_FACTOR);
```

`new BigDecimal(double)` captures the binary representation exactly — `new BigDecimal(0.1)` is
`0.1000000000000000055511151231257827…`. The subsequent `setScale(4, HALF_UP)` hides most of it, but it
can still tip a half-way case the wrong way.

Present in `FirstPriceShadedStrategy` and `SecondPriceStrategy`.

**Fix.** `BigDecimal.valueOf(pacingS)`. Better still, carry the pacing multiplier as a `BigDecimal`
end-to-end and never let it be a `double`.

---

## M2 — Three of five targeting filters are stubs {#m2}

```java
public class BrandSafetyFilter implements TargetingFilter {
    public boolean evaluate(BidRequest request, CampaignIndexData campaignData) {
        return true;      // "Ensure brand safety guidelines"
    }
}
```

`GeoDemographicFilter` and `FormatFilter` are identical `return true` stubs. Only `FinancialFilter` and
`PacingFilter` do anything.

**Effect.** No brand safety enforcement exists. `FormatFilter` returning `true` means a video campaign
can be selected for a banner impression, producing an unrenderable creative — which the exchange will
treat as a failed bid.

These read as implemented, which is the problem: the chain looks five deep and is two deep.

**Fix.** Implement or delete. A stub that returns `true` in a filter chain is worse than an absent
filter, because it advertises a control that does not exist.

---

## M3 — Unvalidated cache values feed pricing directly {#m3}

```java
double pacingS = Double.parseDouble(pacingStr);
BigDecimal maxBid = new BigDecimal(maxBidStr);
```

No range check, and both parses can throw inside a Reactor `subscribe` lambda, where the exception does
not reach the error consumer cleanly — the sink may never complete and the request hangs until the
controller's `tmax` timeout.

**Verified:** `PacingEngineService` clamps the multiplier to `[S_MIN, 1.0]`, so today's producer cannot
emit a value above 1. The gap is that BiddingEngine does not enforce that invariant itself — a manual
Redis edit, a future producer change, or a key collision would let `pacingS > 1.176` bid **above the
advertiser's `maxBid`** through the shaded strategy.

**Fix.** Parse defensively, clamp to `[0, 1]`, and assert `bid <= maxBid` before returning. On a money
path the consumer should not rely on a remote producer's discipline.

---

## M4 — No filter ordering {#m4}

`List<TargetingFilter>` is injected without `@Order` on any implementation, so chain order is Spring's
bean-discovery order — effectively arbitrary and liable to change when a bean is renamed.

The two filters that perform network I/O should run **last**, after the cheap CPU checks have already
rejected the candidate.

**Fix.** `@Order` on each filter, cheapest first.

---

## M5 — Hardcoded hostnames in creatives and tracking {#m5}

```java
event.preAllocatedBid.adm = String.format("<img src='http://cdn.com/sponsored-%s.png' />", selectedCampaign);
event.preAllocatedBid.nurl = String.format("http://event-tracking-service/api/v1/tracking/impression?...");
```

`cdn.com` is a placeholder domain that will not serve an image. `TrackingUrlConstants` holds the same
pattern for the `/serve` path. Both are `http://`, not `https://` — many exchanges reject non-secure
creatives outright.

**Fix.** Externalise both to configuration and default to HTTPS.

---

## M6 — `DEFAULT_GEO = "US"` {#m6}

```java
public static final String DEFAULT_GEO = "US";
```

This is an India-focused platform — rupee amounts, GST charge categories, ONDC integration, Bangalore
coordinates in fixtures. Any request arriving without a geo is bucketed into `"US"`, which will match
no campaigns, producing a silent 204.

**Fix.** Default to a real serving geo, or reject geo-less requests explicitly rather than routing them
to a bucket that cannot match.

---

## M7 — No authentication or rate limiting on either endpoint {#m7}

There is no `SecurityFilterChain`, no `SecurityWebFilterChain` and no `@PreAuthorize` anywhere in the
service. Both `/api/v1/bidding/rtb` and `/api/v1/ads/serve` are open.

For RTB this may be deliberate — exchanges call it — but it then needs IP allowlisting and rate
limiting, neither of which is present. `/ads/serve` is an internal endpoint and should not be reachable
from outside the mesh at all: it lets anyone enumerate live campaigns, their tracking URLs, and the
Base64-encoded winning price embedded in them.

**Fix.** Allowlist the exchange for `/rtb`, restrict `/ads/serve` to internal callers, and add rate
limiting. `CommonLibrary` already ships `RateLimitingService`.

---

## L1 — Multi-tier cache queries L2 twice on a miss {#l1}

```java
this.l1Cache = new CaffeineCacheProvider(l2Cache::get);        // L1 already falls back to L2
...
return l1Cache.get(key).switchIfEmpty(Mono.defer(() -> l2Cache.get(key)));   // and again here
```

Harmless but redundant — a cold key hits Redis twice. Drop the `switchIfEmpty`.

## L2 — Internal ids are never reused {#l2}

`sequenceGenerator` only increments; `removeCampaign` frees the id but nothing reclaims it. Over a long
uptime with campaign churn the id space becomes sparse, which is exactly the case RoaringBitmap
compresses least well. A free-list, or periodic re-indexing, keeps the bitmaps dense.

## L3 — The pricing strategy is hardcoded {#l3}

```java
PricingStrategyType strategyType = PricingStrategyType.FIRST_PRICE_SHADED_STRATEGY;
```

Three strategies exist and one is ever used, chosen at the call site rather than per campaign or per
exchange. Either drive it from campaign configuration or delete the unused strategies — an unused
abstraction still has to be read and maintained.
