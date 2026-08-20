# BiddingEngine — architecture and correctness review

**Date:** 2026-08-20 · **Scope:** all 38 source files (~1,040 LOC) in `src/main/java`, plus config,
messaging and the serving paths · **Method:** read every file; each finding below was verified against
the code, and where a claim could not be verified it is marked as such.

## Verdict

The design intent is genuinely good — RCU index with immutable snapshots, RoaringBitmap geo matching,
LMAX Disruptor on the bid path, a multi-tier cache, strategy-based pricing. Those are the right
primitives for an ad server.

**The execution has five defects that stop it working in production**, and four of the five are
invisible at runtime: they produce "no ad" rather than an error. An ad server that silently serves
nothing looks healthy on every dashboard except revenue.

| # | Finding | Severity | Effect |
|---|---|---|---|
| [C1](FINDINGS.md#c1) | Redis config is nested under `outbox:`, not `spring:` | **Critical** | No Redis outside localhost → **zero bids in every deployed environment** |
| [C2](FINDINGS.md#c2) | The campaign index has no bootstrap | **Critical** | **Restart ⇒ zero ads served**, silently, possibly forever |
| [C3](FINDINGS.md#c3) | `/ads/serve` never applies the filter chain | **Critical** | Sponsored listings ignore budget and brand safety |
| [C4](FINDINGS.md#c4) | Disruptor response objects mutated in an async callback | **Critical** | Cross-request corruption under load — wrong price/campaign in a bid |
| [C5](FINDINGS.md#c5) | L1 cache serves budget data up to 10 min stale, with no expiry | **Critical** | Overspend after budget exhaustion |
| [H1](FINDINGS.md#h1) | Blocking `.block(50ms)` inside the Disruptor handler | High | Defeats the Disruptor; serialises all RTB behind one thread |
| [H2](FINDINGS.md#h2) | `match()` materialises every campaign in a geo | High | O(all campaigns) allocation per request |
| [H3](FINDINGS.md#h3) | Every index write deep-clones the whole geo index | High | O(total campaigns) per campaign event |
| [H4](FINDINGS.md#h4) | No auction — `findFirst()` picks the oldest eligible campaign | High | Revenue left on the table; advertisers cannot outbid |
| [M1](FINDINGS.md#m1) | `new BigDecimal(double)` on the price path | Medium | Precision noise in money arithmetic |
| [M2](FINDINGS.md#m2) | 3 of 5 targeting filters are `return true` stubs | Medium | No brand safety, no format validation |
| [M3](FINDINGS.md#m3) | Unvalidated cache values feed pricing directly | Medium | A corrupt Redis value can bid above `maxBid` |
| [M4](FINDINGS.md#m4) | No filter ordering | Medium | Expensive filters may run before cheap ones |
| [M5](FINDINGS.md#m5) | Hardcoded `cdn.com` and tracking hostnames | Medium | Broken creatives; not environment-portable |
| [M6](FINDINGS.md#m6) | `DEFAULT_GEO = "US"` on an India platform | Medium | Ads silently unmatched for geo-less requests |
| [M7](FINDINGS.md#m7) | No auth or rate limiting on either serving endpoint | Medium | Open bid endpoint; free inventory enumeration |
| [L1](FINDINGS.md#l1) | Multi-tier cache queries L2 twice on a miss | Low | Doubles Redis load on cold keys |
| [L2](FINDINGS.md#l2) | Monotonic internal ids, never reused | Low | Bitmap density degrades over a long uptime |
| [L3](FINDINGS.md#l3) | Pricing strategy hardcoded at the call site | Low | Strategy pattern unused |

## Where to start

**C1 first — it is a one-line YAML indentation fix** and nothing else can be validated in a deployed
environment until it lands. Then C2, which is the difference between "survives a restart" and
"doesn't". C3 and C5 are both direct overspend paths. C4 is the subtlest and the most dangerous under
load.

Everything in [DESIGN.md](DESIGN.md) is secondary to those five.

## Files in this review

| File | Contents |
|---|---|
| [FINDINGS.md](FINDINGS.md) | Every defect with the code, the mechanism, and the fix |
| [DESIGN.md](DESIGN.md) | Architecture recommendations beyond bug-fixing |
| [VERIFICATION.md](VERIFICATION.md) | How to prove each finding, and prove each fix |

## What this review did not cover

- **Load behaviour is reasoned, not measured.** C4 and H1 are argued from the code; neither was
  reproduced under load. VERIFICATION.md gives the experiment for each.
- `OpenRtbRequestParser` was read but not audited against the OpenRTB 2.5 spec.
- The Disruptor `YieldingWaitStrategy` CPU cost is noted but not profiled.
