# Verification

How to prove each finding is real before fixing it, and prove the fix afterwards. Written this way
deliberately: several findings here are *silent*, so "the service still starts" is not evidence of
anything.

## Ground rules

- **Reproduce before fixing.** Four of the five criticals produce `204 No Content`, which is also the
  correct response for "no matching campaign". If you cannot tell those apart, you cannot tell whether
  your fix worked.
- **Every fix needs a negative control**: revert it and watch the check fail again. A passing test is
  not evidence on its own — this codebase had 21 tests that asserted nothing and stayed green for
  months.
- `mvn -o clean test -Dnet.bytebuddy.experimental=true`, always with `clean`.

---

## C1 — Redis config nesting

**Prove it:**

```bash
cd BiddingEngine && python3 -c "
import yaml; d=yaml.safe_load(open('src/main/resources/application.yml'))
print('spring.data present?', 'data' in d.get('spring',{}))
print('outbox:', d.get('outbox'))"
```

Expected today: `False`, and the redis block appearing under `outbox`.

**Prove the fix:** the same script prints `True`, and `outbox` contains only `enabled`. Then start
against a non-localhost Redis (`REDIS_HOST=redis`) and confirm a bid returns 200 rather than 204.

**Negative control:** re-indent it back under `outbox:` and confirm bids return to 204.

## C2 — No index bootstrap

**Prove it:**

1. Start the service, publish an `AD_CAMPAIGN_CREATED` event, confirm `/api/v1/ads/serve` returns a
   listing.
2. Restart the service **without** republishing.
3. The same request returns an empty list.

**Prove the fix:** after step 2 the same request still returns the listing, and the readiness probe
stays down until the bootstrap load completes.

**Instrument first.** Add the indexed-campaign-count metric from DESIGN.md §3 before fixing — otherwise
step 3 looks identical to "no campaigns exist".

## C3 — `/ads/serve` skips the filter chain

**Prove it without a running service** — the field is provably never read:

```bash
grep -n "filterChain" src/main/java/com/fooddelivery/advertisement/bidding/controller/InternalAdController.java
```

Three hits: declaration, constructor parameter, assignment. No fourth.

**Prove it at runtime:** set an advertiser's wallet balance to `0` in Redis, then call `/ads/serve`.
Today it still returns that advertiser's campaign. Call `/api/v1/bidding/rtb` for the same campaign and
it correctly returns 204 — the two paths disagree, which is the bug.

**Prove the fix:** `/ads/serve` returns an empty list for the zero-balance advertiser.
**Negative control:** restore the balance, confirm the listing comes back — so you know you filtered on
budget and did not simply break the endpoint.

## C4 — Slot reuse during the async callback

This one needs load; reasoning is not enough, and a wrong conclusion here is expensive either way.

**Prove it:**

1. Add a temporary assertion at the top of the `Mono.zip(...).subscribe` callback capturing
   `event.preAllocatedBid.identityHashCode` and the campaign id it is about to write.
2. Drive more than 262,144 concurrent-ish requests (the ring buffer size) with Redis latency injected —
   `redis-cli DEBUG SLEEP`, or a toxiproxy delay — so callbacks stay outstanding while the buffer wraps.
3. Assert that the campaign id written in the callback matches the one selected for *that* request.

A mismatch is the bug. If you cannot make it mismatch, say so in this file — the finding should be
downgraded rather than quietly assumed.

**Prove the fix:** the same load with per-callback allocation shows no mismatch, and response bodies
correlate 1:1 with their request ids.

## C5 — Stale budget in L1

**Prove it:**

1. Set `ad:wallet:balance:<advertiserId>` to a positive value; make one bid so the key enters L1.
2. Set it to `0` in Redis directly.
3. Bid again immediately — the bid still goes through, because L1 holds the old value.
4. It keeps going through for up to 10 minutes.

**Prove the fix:** step 3 refuses the bid within the new expiry window (seconds), or immediately if you
implement event-driven invalidation.

**Negative control:** restore the long `refreshAfterWrite` and confirm the stale bid returns.

## H1 — Blocking in the Disruptor handler

**Prove it:**

```bash
grep -rn "\.block(" src/main/java/com/fooddelivery/advertisement/bidding/filter/
```

Both `FinancialFilter` and `PacingFilter`. Then inject 200 ms of Redis latency and measure p99 on
`/rtb` under concurrency — throughput should collapse toward `1 / (latency × filters × candidates)`,
because a single handler thread serialises everything.

**Prove the fix:** with budget and pacing resolved from the in-memory index (DESIGN.md §1), injected
Redis latency no longer affects `/rtb` p99 at all.

## H4 — No auction

**Prove it:** index two campaigns for the same geo, the older with `maxBid = 1.00`, the newer with
`maxBid = 100.00`. Bid. The response carries the **older, cheaper** campaign every time.

**Prove the fix:** the same setup returns the 100.00 campaign, and reversing the insertion order does
not change the winner.

## M1 — `BigDecimal(double)`

A unit test is enough, and it needs no infrastructure:

```java
assertThat(new FirstPriceShadedStrategy().calculateBid("c", new BigDecimal("10.00"), 0.1))
        .isEqualByComparingTo("0.8500");
```

Then assert the intermediate is exact by switching to `BigDecimal.valueOf` and comparing unscaled
values. Note `isEqualByComparingTo`, not `isEqualTo` — `BigDecimal.equals` compares scale, so
`0.8500` and `0.85` are unequal under `equals`.

## M3 — Unvalidated cache values

**Prove it:** set the pacing key to `2.0` manually and confirm the emitted bid exceeds `maxBid`
(`2.0 × 0.85 = 1.7 ×`). Then set it to `not-a-number` and confirm the request hangs until `tmax`
rather than failing fast.

**Prove the fix:** both cases produce a clamped bid or a clean 204, and `bid <= maxBid` always holds.

---

## Regression suite this service still lacks

Whatever is fixed, these are the tests worth leaving behind — none of them exist today:

| Test | Guards |
|---|---|
| Index bootstrap populates from CampaignService on startup | C2 |
| `/ads/serve` and `/rtb` reject a zero-budget advertiser identically | C3 |
| Highest `maxBid` wins among eligible campaigns | H4 |
| Bid never exceeds `maxBid` for any pacing input, including malformed | M3 |
| Budget change in Redis is respected within the SLA | C5 |
| Response bodies correlate 1:1 with request ids under load | C4 |

The existing `fetchAds` contract does not cover any of them, and cannot in its current form — see
`RandomDocuments/claude/DECISIONS_NEEDED.md` decision 12.
