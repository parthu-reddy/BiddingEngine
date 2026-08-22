# Real-Time Bidding Engine (BiddingEngine)

## Overview
The `BiddingEngine` is the highly concurrent, ultra-low-latency core of the advertisement platform. It receives OpenRTB requests from Supply-Side Platforms (SSPs) or Ad Exchanges, evaluates millions of targeting permutations, calculates optimized bids via ML shading algorithms, and responds within a strict SLA (typically < 50-100ms).

## Core Responsibilities & Architecture

### 1. Protocol Integration
- Uses the `OpenRtbRequestParser` to parse incoming `BidRequest` payloads.
- **Protobuf Native**: Strictly uses Protobuf for binary deserialization to minimize CPU parsing overhead and GC pressure. Supports JSON as a fallback for non-compliant exchanges.
- Reads the dynamic `tmax` (timeout) field per request to dictate execution limits.

### 2. Multi-Tier Caching (No Blocking I/O)
- **Constraint**: No network-bound database queries are permitted within the RTB critical path.
- **L1 Cache (Caffeine)**: In-process heap caching providing sub-microsecond retrieval.
- **L2 Cache (Redis Cluster)**: Distributed caching providing 1-5ms fallback.
- Implements asynchronous cache-aside refresh to preemptively renew data before TTL expires, avoiding cache avalanches.

```mermaid
flowchart TB
    Request["BiddingEngine needs<br/>campaign data"]

    Request --> L1{"L1 Caffeine<br/>In-Process Heap Cache"}

    L1 -->|"✅ HIT<br/>sub-μs"| Return["Return data<br/>to caller"]
    L1 -->|"❌ MISS"| L2{"L2 Redis Cluster<br/>Distributed Cache"}

    L2 -->|"✅ HIT<br/>1-5ms"| PopulateL1_1["Populate L1 Caffeine<br/>with randomized TTL"]
    PopulateL1_1 --> Return

    L2 -->|"❌ MISS"| Coalesce{"Thundering Herd<br/>Protection"}
    Coalesce -->|"First thread"| DB["Fetch from PostgreSQL<br/>(canonical source)"]
    Coalesce -->|"Other threads"| Wait["Wait for first<br/>thread's result"]

    DB --> PopulateL2["Populate L2 Redis<br/>with randomized TTL"]
    PopulateL2 --> PopulateL1_2["Populate L1 Caffeine"]
    PopulateL1_2 --> Return
    Wait --> Return

    subgraph Background["Async Background Refresh"]
        Monitor["Monitor near-expiry keys"]
        Monitor --> Refresh["Preemptively refresh<br/>before TTL expires"]
        Refresh --> UpdateL2["Update L2 Redis"]
        UpdateL2 --> UpdateL1["Update L1 Caffeine"]
    end

    subgraph Resilience["Failure Scenarios"]
        RedisDown["Redis connection fails"]
        RedisDown --> L1Only["Degrade to L1 only<br/>Do NOT block critical path"]
        InstanceCrash["BiddingEngine pod crashes"]
        InstanceCrash --> L1Destroyed["L1 destroyed"]
        L1Destroyed --> WarmFromL2["New pod: warm L1<br/>from L2 Redis<br/>during readiness probe"]
    end

    style Return fill:#4caf50,color:#fff
    style L1 fill:#e3f2fd
    style L2 fill:#fff3e0
    style DB fill:#fce4ec
    style L1Only fill:#ff9800,color:#fff
    style L1Destroyed fill:#f44336,color:#fff
```

### 3. Index Concurrency

**As implemented.** `CampaignMatcher` guards its four maps and the Roaring Bitmap geo index with a
single `ReentrantReadWriteLock`. Bid evaluation takes the read lock; index mutations arriving from
`ad-events` take the write lock. Concurrent reads do not block each other, but a write blocks all
readers for its duration.

**Design intent, not built.** The LMAX Disruptor dependency has been removed from `pom.xml`; it was
never referenced in the source. The lock-free ring buffer, pre-allocated at startup with zero allocations on
the critical path and cache-line padded sequence counters, remains a target — worth revisiting only
if a benchmark shows the write lock is a real bottleneck at production write rates.

```mermaid
flowchart LR
    subgraph Producers["Producer Threads"]
        P1["Bid Request<br/>Handler"]
        P2["Tracking Event<br/>Handler"]
    end

    subgraph Ring["Bid pipeline (design intent: Disruptor ring buffer)"]
        direction TB
        Slot["Pre-allocated slots<br/>Size: power of 2<br/>Slot = seq AND (size-1)"]
        Seq["Atomic Sequence Counters<br/>Cache-line padded<br/>64 or 128 bytes"]
        NoGC["Zero object allocation<br/>No 'new' keyword<br/>Mutable pre-allocated objects"]
    end

    subgraph Consumers["Consumer Threads"]
        C1["Auction Evaluator"]
        C2["Event Processor"]
    end

    P1 -->|"Lock-free<br/>write"| Ring
    P2 -->|"Lock-free<br/>write"| Ring
    Ring -->|"Lock-free<br/>read"| C1
    Ring -->|"Lock-free<br/>read"| C2

    subgraph AntiPatterns["❌ Anti-Patterns"]
        ABQ["ArrayBlockingQueue<br/>Kernel mutexes<br/>Context switching"]
        NewKW["new keyword<br/>during processing<br/>GC pauses"]
    end

    subgraph EdgeCases["Edge Cases"]
        Full["Buffer Full →<br/>Fast-fail / Backpressure<br/>Never block past SLA"]
        FalseShare["False Sharing →<br/>Pad to cache line<br/>64/128 bytes"]
    end

    style Ring fill:#e8f5e9
    style AntiPatterns fill:#ffebee
    style EdgeCases fill:#fff8e1
```

### 4. Inverted Indexing (RoaringBitmaps)
- Maintains an in-memory inverted index of eligible campaigns using `RoaringBitmap`.
- Performs rapid boolean AND/OR bitwise intersections across targeting dimensions (Geo, Device, Demographics, Dayparting, Context).
- **Memory Optimization**: Roaring Bitmaps divide 32-bit integers into chunks. If a chunk contains fewer than 4,096 elements, it is stored as a highly memory-efficient sorted array of 16-bit integers. If it exceeds this threshold, it upgrades to a traditional bitset container. Contiguous runs are compressed using Run-Length Encoding.
- **Index updates (as implemented)**: Kafka updates mutate the bitmaps in place under a write lock,
  which briefly blocks readers. Read-Copy-Update / double-buffering — building a new snapshot and
  swapping an `AtomicReference` so readers are never blocked — is design intent, not current
  behaviour.

### 5. Targeting Filter Chain (Chain of Responsibility)
Filters eligible campaigns from cheapest to most expensive computational cost:

```mermaid
flowchart LR
    Start["Candidate<br/>Campaign"] --> F1

    subgraph Chain["Filter Chain (ordered by cost)"]
        F1{"1. Format Filter<br/>Ad size match?<br/>⚡ Instant"}
        F2{"2. Geo & Demo Filter<br/>Bitmap intersection<br/>📍 Fast"}
        F3{"3. Financial Filter<br/>Budget remaining?<br/>💰 Fast"}
        F4{"4. Pacing Filter<br/>Random roll vs s<br/>🎲 Fast"}
        F5{"5. Brand Safety Filter<br/>Domain blocklist<br/>🛡️ Medium"}
    end

    F1 -->|"✅ Pass"| F2
    F1 -->|"❌ Fail"| Reject["REJECTED<br/>Save CPU cycles"]
    F2 -->|"✅ Pass"| F3
    F2 -->|"❌ Fail"| Reject
    F3 -->|"✅ Pass"| F4
    F3 -->|"❌ Fail"| Reject
    F4 -->|"✅ Pass"| F5
    F4 -->|"❌ Fail"| Reject
    F5 -->|"✅ Pass"| Accept["✅ ELIGIBLE<br/>→ BidPricer"]
    F5 -->|"❌ Fail"| Reject

    style Reject fill:#ff4444,color:#fff
    style Accept fill:#00cc66,color:#fff
    style F1 fill:#e8f5e9
    style F2 fill:#e8f5e9
    style F3 fill:#fff3e0
    style F4 fill:#fff3e0
    style F5 fill:#fce4ec
```

If any filter returns `false`, the chain terminates immediately to conserve CPU cycles.

### 6. Bid Pricing (Strategy Pattern & Bid Shading)
The `BidPricer` combines budget pacing multipliers and auction-specific pricing strategies:
- **FirstPriceShadedStrategy**: Shades the bid down from the advertiser's max by a fixed
  `SHADING_FACTOR` constant, scaled by the pacing multiplier. The clearing-price model in the diagram
  below is *design intent, not built* — no prediction happens today.
- **SecondPriceStrategy**: Returns the true max bid, relying on exchange mechanics.
- **FixedPricingStrategy**: Static bid execution.
Always validates the final bid against the exchange's `bidfloor`.

```mermaid
flowchart TB
    Start["Advertiser Max Bid: $5.00"]

    Start --> Gather["Gather Historical Data"]
    Gather --> D1["Historical clearing prices<br/>for this publisher"]
    Gather --> D2["SSP identifier &<br/>exchange characteristics"]
    Gather --> D3["Historical win rate<br/>at various price points"]
    Gather --> D4["Contextual signals<br/>(time, geo, device)"]

    D1 --> ML["ML Model: Predict<br/>lowest clearing price<br/>to win this auction"]
    D2 --> ML
    D3 --> ML
    D4 --> ML

    ML --> Prediction["Predicted clearing price: $2.50"]

    Prediction --> Shade["Apply shading margin<br/>$2.50 + $0.10 = $2.60"]

    Shade --> FloorCheck{"Bid ≥ bidfloor?"}
    FloorCheck -->|"Yes"| Submit["Submit shaded bid: $2.60<br/>Savings: $2.40 (48%)"]
    FloorCheck -->|"No"| FloorBid{"Bid at floor<br/>or no bid?"}
    FloorBid -->|"Bid at floor"| SubmitFloor["Submit bid = bidfloor"]
    FloorBid -->|"No bid"| NoBid["HTTP 204 No Content"]

    subgraph ColdStart["Edge Case: Cold Start"]
        NoData["No historical data<br/>for new publisher/SSP"]
        NoData --> Conservative["Default to conservative<br/>markdown or second-price logic"]
    end

    style Start fill:#2196f3,color:#fff
    style Submit fill:#4caf50,color:#fff
    style NoBid fill:#f44336,color:#fff
    style Prediction fill:#ff9800,color:#fff
```

## RTB Critical Path Flow Diagram
The core auction flow showing every step within the latency budget (< 50ms):

```mermaid
sequenceDiagram
    autonumber
    participant SSP as SSP / Ad Exchange
    participant GW as API Gateway
    participant Parser as OpenRtbRequestParser
    participant Cache as L1 Caffeine / L2 Redis
    participant Index as RoaringBitmap Index
    participant Matcher as CampaignMatcher
    participant Chain as Targeting Filter Chain
    participant Pricer as BidPricer
    participant Strategy as PricingStrategy

    Note over SSP,Strategy: ⏱️ Total latency budget: tmax (typically 50-100ms)

    SSP->>GW: BidRequest (Protobuf/JSON)<br/>id, tmax, at, Imp, User/Device
    GW->>GW: Rate limit check · TLS session resume
    GW->>Parser: Forward raw bytes

    Parser->>Parser: Deserialize Protobuf → Java objects
    Parser->>Parser: Extract tmax → set dynamic timeout
    Parser->>Parser: Validate payload (malformed → HTTP 204)
    Parser->>Parser: Check auction type (unknown at → HTTP 204)

    Parser->>Cache: Fetch campaign params, targeting rules, budget status
    alt L1 Cache Hit (Caffeine)
        Cache-->>Parser: Sub-microsecond response
    else L1 Miss → L2 Hit (Redis)
        Cache-->>Parser: 1-5ms response
    else Both Miss
        Cache->>Cache: Fetch from DB → populate L2 → populate L1
        Cache-->>Parser: Response (async refresh scheduled)
    end

    Parser->>Index: Pass parsed BidRequest
    Index->>Matcher: Boolean AND/OR bitwise intersections
    Note over Index,Matcher: Device ∩ Geo ∩ Demographic ∩ Daypart ∩ Context

    Matcher-->>Chain: List of candidate campaigns

    loop For each candidate campaign
        Chain->>Chain: 1. Format Filter (ad size match)
        Chain->>Chain: 2. Geo & Demographic Filter (bitmap)
        Chain->>Chain: 3. Financial Filter (budget check)
        Chain->>Chain: 4. Pacing Filter (roll vs multiplier s)
        Chain->>Chain: 5. Brand Safety Filter (blocklist)
        Note over Chain: Any filter returns false → STOP
    end

    Chain-->>Pricer: Eligible campaigns

    alt No eligible campaigns
        Pricer-->>GW: HTTP 204 No Content
    else Campaigns found
        Pricer->>Cache: Frequency cap check (Redis INCR/EXPIRE)<br/>CampaignID+DeviceID daily limit
        alt Frequency cap exceeded
            Note over Pricer: Skip campaign — user hit daily limit
        else Under cap
            Pricer->>Pricer: Consult pacing multiplier s
            Pricer->>Strategy: Delegate to strategy (based on at)
            alt at = 1 (First-Price)
                Strategy->>Strategy: FirstPriceShadedStrategy<br/>Analyze historical prices, SSP, win rates
                Strategy-->>Pricer: Shaded bid (e.g., $2.60)
            else at = 2 (Second-Price)
                Strategy->>Strategy: SecondPriceStrategy<br/>Return true max bid
                Strategy-->>Pricer: Max bid (e.g., $5.00)
            else Fixed
                Strategy->>Strategy: FixedPricingStrategy
                Strategy-->>Pricer: Static bid
            end

            Pricer->>Pricer: Validate bid ≥ bidfloor
            Pricer->>Pricer: BigDecimal — no floats/doubles

            Pricer->>GW: BidResponse + SeatBid<br/>+ ${AUCTION_PRICE} macro in markup
        end
    end

    GW->>SSP: BidResponse (Protobuf)
    Note over GW,SSP: Total time < tmax ✅
```

## SOLID Principles & GoF Design Patterns
This service is designed with highly isolated responsibilities to prevent the codebase from deteriorating into an unmanageable monolith:

- **SRP (Single Responsibility Principle)**: Responsibilities are strictly isolated. The `OpenRtbRequestParser` handles binary deserialization, `CampaignMatcher` handles set intersections, and `BidPricer` handles math. If the OpenRTB consortium releases a new protocol version, only the parser requires modification.
- **OCP (Open/Closed Principle)**: New targeting filters (e.g. sentiment analyzers) can be introduced via the `TargetingFilter` interface without modifying the core RTB loop. 
- **LSP (Liskov Substitution Principle)**: Multi-tier caching relies entirely on a generic `CacheProvider` interface allowing seamless swapping of implementations (`CaffeineCacheProvider`, `RedisCacheProvider`, `NoOpCacheProvider`) without altering execution behavior.
- **DIP (Dependency Inversion Principle)**: `CampaignRepository`, `SSPClient`, and Http clients are injected at runtime via Spring Boot/Guice (IoC container) for superior testing capability. The `new` keyword is never used for infrastructure dependencies.

```mermaid
classDiagram
    class BiddingEngine {
        <<Core Service>>
    }

    class OpenRtbRequestParser {
        <<SRP>>
        +parse(bytes) BidRequest
    }

    class CampaignMatcher {
        <<SRP>>
        +match(BidRequest) List~Campaign~
    }

    class BidPricer {
        <<SRP>>
        +price(campaigns, ctx) BidResponse
    }

    class CacheProvider {
        <<Interface - LSP>>
        +get(key) Object
        +put(key, value) void
    }

    class CaffeineCacheProvider {
        +get(key) Object
        +put(key, value) void
    }

    class RedisCacheProvider {
        +get(key) Object
        +put(key, value) void
    }

    class NoOpCacheProvider {
        +get(key) Object
        +put(key, value) void
    }

    class TargetingFilter {
        <<Interface - OCP>>
        +check(BidRequest, Campaign) boolean
    }

    class FormatFilter
    class FinancialFilter
    class PacingFilter

    class PricingStrategy {
        <<Interface - Strategy>>
        +calculateBid(Campaign, ImpressionContext) BigDecimal
    }

    class FixedPricingStrategy
    class FirstPriceShadedStrategy
    class SecondPriceStrategy

    CacheProvider <|.. CaffeineCacheProvider
    CacheProvider <|.. RedisCacheProvider
    CacheProvider <|.. NoOpCacheProvider

    TargetingFilter <|.. FormatFilter
    TargetingFilter <|.. FinancialFilter
    TargetingFilter <|.. PacingFilter

    PricingStrategy <|.. FixedPricingStrategy
    PricingStrategy <|.. FirstPriceShadedStrategy
    PricingStrategy <|.. SecondPriceStrategy

    BiddingEngine ..> OpenRtbRequestParser : uses
    BiddingEngine ..> CampaignMatcher : uses
    BiddingEngine ..> BidPricer : uses
    BiddingEngine ..> CacheProvider : depends on
    BiddingEngine ..> TargetingFilter : chains
    OpenRtbRequestParser ..> CampaignMatcher : feeds
    CampaignMatcher ..> BidPricer : feeds
    BidPricer ..> PricingStrategy : delegates
```

## Resilience & Edge Cases
All subcomponents implement strict fault-tolerance:

```mermaid
flowchart TB
    subgraph Failures["Failure Scenarios"]
        F1["BudgetPacingService<br/>high latency"]
        F2["Redis Cluster<br/>connection failure"]
        F3["Kafka broker<br/>temporarily down"]
        F4["PostgreSQL<br/>partition / outage"]
        F5["BiddingEngine pod<br/>OOM crash"]
        F6["Reporting DB<br/>connection pool exhausted"]
        F7["SSP sends<br/>malformed payload"]
        F8["Price decryption<br/>key rotation mismatch"]
        F9["Flash crowd<br/>RPS spike"]
    end

    subgraph Mitigations["Mitigation Strategies"]
        M1["Circuit Breaker trips →<br/>Fallback to cached pacing limits"]
        M2["Degrade to L1 Caffeine only<br/>Do not block critical path"]
        M3["DLQ + Retry with<br/>exponential backoff"]
        M4["Bulkhead isolates<br/>affected thread pool"]
        M5["K8s readiness probe detects →<br/>Auto-replace pod<br/>Warm L1 from L2"]
        M6["Bulkhead: reporting pool<br/>isolated from bidding pool"]
        M7["Catch exception →<br/>HTTP 204 No Content<br/>Record telemetry"]
        M8["DLQ + Alert →<br/>No billing processed<br/>Fail fast"]
        M9["API Gateway:<br/>Rate limiting + Load shedding"]
    end

    F1 --> M1
    F2 --> M2
    F3 --> M3
    F4 --> M4
    F5 --> M5
    F6 --> M6
    F7 --> M7
    F8 --> M8
    F9 --> M9

    subgraph Principle["Core Principle"]
        P["Reporting/Campaign portal failures<br/>NEVER impact the RTB critical path"]
    end

    style Failures fill:#ffebee
    style Mitigations fill:#e8f5e9
    style Principle fill:#e3f2fd
```

- **JVM Warmup**: Uses dummy-traffic warmup scripts in Kubernetes readiness probes to trigger JIT compilation before routing live traffic. GraalVM Native Image support for Ahead-of-Time (AOT) compilation is utilized to drastically reduce startup time and memory footprint.
- **Malformed Payloads**: Fails fast (HTTP 204 No Content).
- **No Campaigns Found**: Returns HTTP 204 instead of constructing empty responses.

## Observability & Tracing (OpenTelemetry)
Deep observability is maintained via **OpenTelemetry**. Distributed tracing headers are injected into every incoming network request. These highly detailed traces flow into a centralized monitoring system (e.g., Jaeger/Grafana Tempo), allowing engineers to visualize the exact microsecond-level latency of a bid request as it passes through the API Gateway, checks the Caffeine L1 cache, intersects the Roaring Bitmaps, and formats the final OpenRTB binary response.

## Ecosystem Integration Points
How this service integrates with the broader Food Delivery platform:

- **CustomerApplication**: `CustomerApplication` calls `BiddingEngine` via FeignClient (`POST /api/v1/ads/serve`) when a user searches for food. The `BiddingEngine` evaluates targeting (geo, time, context) and returns sponsored listings. `CustomerApplication` implements a fallback that returns an empty list on failure, ensuring ad failures never block core food ordering.
- **ApiGateway**: OpenRTB bidding routes (`/api/v1/ads/**`) are mapped here for external SSPs and ad exchanges.
- **ConfigService**: Externalizes bid-shading parameters and cache TTLs.
