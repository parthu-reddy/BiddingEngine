# Design recommendations

Beyond the defects in [FINDINGS.md](FINDINGS.md). These are structural, and worth doing in this order.

---

## 1. Move budget and pacing out of the request path

**Today:** every bid does two Redis round-trips (`maxBid`, `pacing`), plus two more inside the blocking
filters — four network hops on the critical path of a request with a `tmax` typically around 100 ms.

**Proposal:** fold `maxBid`, the pacing multiplier and a budget-exhausted flag into `CampaignIndexData`
in the in-memory index, updated by the same Kafka consumer that already maintains it.

That single change fixes or removes four findings at once:

- **H1** disappears — filters become pure CPU, no blocking, no reactive rewrite needed.
- **C5** disappears — there is no L1 staleness window because there is no L1 read.
- **C4** becomes tractable — with no async I/O, the response can be built and completed *inside* the
  Disruptor handler, which is what makes slot-owned pre-allocation safe in the first place.
- **H4** becomes possible — you cannot rank candidates by price when the price needs a network call
  per candidate; you can when it is a field.

The cost is that the index must be fed reliably. `BudgetLimitingService` already publishes to
`ad-events`, and `CampaignEventConsumer` already listens. What is missing is a periodic reconciliation
sweep so a dropped event cannot leave a campaign bidding on a stale budget forever.

**This is the highest-leverage change in this document.** Everything else is local.

## 2. Decide what the Disruptor is for, then commit to it

Right now the Disruptor sits between two reactive layers and its handler immediately hands off to
another async chain. It buys nothing in that arrangement — the mechanical-sympathy benefit comes from
doing the whole unit of work on the handler thread without blocking or handing off.

Two coherent options:

**A. Keep the Disruptor, make the work synchronous.** With recommendation 1 in place there is no I/O on
the path, so match → filter → rank → price → serialise can all run on the handler thread. Then
pre-allocation is genuinely safe and genuinely zero-allocation, and one thread can plausibly handle a
large QPS.

**B. Drop the Disruptor, stay reactive end to end.** Make the filters return `Mono<Boolean>` and compose
the pipeline. Simpler, idiomatic for a WebFlux service, and one fewer concurrency model to reason about.

**A if latency at high QPS is the goal; B if maintainability is.** What is not defensible is the current
middle position, which pays the complexity of both and the benefit of neither.

Also: `YieldingWaitStrategy` spins a core permanently. That is the right trade at high sustained QPS
and pure waste at low traffic — pick deliberately, and consider `BlockingWaitStrategy` for non-production
profiles.

## 3. Make the index authoritative and restartable

Beyond the missing bootstrap (**C2**), the index has no observable state at all. Add:

- **A bootstrap load** gating readiness, so a pod with an empty index never receives traffic.
- **Periodic reconciliation** against CampaignService — a full resync on an interval, so a dropped or
  out-of-order Kafka event self-heals instead of persisting until the next restart.
- **Metrics**: indexed campaign count, per-geo counts, last-successful-sync timestamp. Today, "index is
  empty" and "no campaigns match this geo" look identical from outside — which is what makes C2 silent.

## 4. Give "no bid" a reason

Every failure path returns `204 No Content`:

- no campaigns in the index (restart)
- no campaigns for the geo
- all campaigns filtered out
- `maxBid` missing from Redis (misconfiguration)
- Redis unreachable
- pricing threw

These are operationally very different and indistinguishable today. A counter dimensioned by reason —
`bid_skipped_total{reason="no_maxbid"}` — turns C1 and C2 from silent revenue loss into an obvious
alert. **This is the cheapest high-value change here.**

## 5. Type the request/response boundary

`AdvertisementClient.fetchAds` returns bare `Object`, which is why the `fetchAds` contract could assert
`{adId, content}` for years while the API returned `List<SponsoredListingDTO>`. Publish a shared DTO
and type both ends. Covered in more detail as decision 13 in
`RandomDocuments/claude/DECISIONS_NEEDED.md` — the same pattern affects 40 of 61 Feign methods
platform-wide.

## 6. Separate the two serving paths properly

`/api/v1/bidding/rtb` and `/api/v1/ads/serve` duplicate match → filter → price → build, with different
bugs in each copy — which is exactly how `/ads/serve` ended up with no filtering at all (**C3**).

Extract one `AdSelectionService` returning ranked, priced, filtered candidates, and let both controllers
adapt its output to their own wire format. One code path, one place for the filter chain, one place to
fix.

---

## Suggested sequence

| Step | Work | Why here |
|---|---|---|
| 1 | C1 (YAML), C3 (apply filters), M5 (hostnames) | Hours. Nothing else can be validated in a deployed environment until C1 lands. |
| 2 | Recommendation 4 (no-bid reasons) + C2 (bootstrap) | Makes the remaining problems observable before you change more code. |
| 3 | Recommendation 1 (budget into the index) | Resolves C5, H1, and unblocks H4. |
| 4 | Recommendation 2 (commit to one concurrency model) → fixes C4 | Only safe once step 3 removes the async I/O. |
| 5 | H4 (real auction), H2, H3 | Revenue and scale, once the foundation is sound. |
| 6 | M2 (implement or delete stub filters), M7 (auth), recommendations 5–6 | Hardening and cleanup. |

Steps 1 and 2 are small and independently valuable. Step 3 is the one that pays for itself repeatedly.
