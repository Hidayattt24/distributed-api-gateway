# M7 limiter comparison

Hypothesis: with capacity 10 and refill 5/s, a fixed window admits a larger burst across its two-second boundary. Per-client keys isolate clients but raise total allowance.

Method: deterministic virtual time, sequential decisions, fresh local limiter for every row. No HTTP, Redis, concurrency, random input, or real sleeping. See requests.csv and environment.properties.

| Workload | Algorithm | Key | Allowed | Rejected | Max accepted in 2s | A | B | Mean decision ns* |
|---|---|---|---:|---:|---:|---:|---:|---:|
|burst|token-bucket|route|10|50|10|5|5|1328915|
|burst|token-bucket|client|20|40|20|10|10|2455|
|burst|fixed-window|route|10|50|10|5|5|10755|
|burst|fixed-window|client|20|40|20|10|10|2335|
|boundary|token-bucket|route|10|10|10|10|0|4960|
|boundary|token-bucket|client|10|10|10|10|0|2830|
|boundary|fixed-window|route|20|0|20|20|0|1675|
|boundary|fixed-window|client|20|0|20|20|0|1085|
|steady|token-bucket|route|59|41|19|50|9|1862|
|steady|token-bucket|client|100|0|20|50|50|2396|
|steady|fixed-window|route|50|50|10|25|25|1011|
|steady|fixed-window|client|100|0|20|50|50|932|
|skewed|token-bucket|route|10|50|10|10|0|2018|
|skewed|token-bucket|client|20|40|20|10|10|1500|
|skewed|fixed-window|route|10|50|10|10|0|1166|
|skewed|fixed-window|client|20|40|20|10|10|905|

*Decision timing includes JVM and Reactor overhead without warmup; do not use it for throughput claims. The boundary row shows fixed-window edge burst; the skewed row shows client isolation only when keys are per-client. This experiment does not measure Redis correctness, network latency, or gateway throughput.
