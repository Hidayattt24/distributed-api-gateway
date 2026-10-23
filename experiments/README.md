# M7 experiments

Run the deterministic local comparison on JDK 21:

```powershell
.\gradlew.bat :experiments:run --args='--out results/latest'
```

Open `experiments/results/latest/report.md`. The same folder contains `requests.csv` (one decision per row) and `environment.properties`. `experiments/baseline/` contains one recorded run for review. Generated runs under `results/` are ignored by Git.

The four workloads are a 60-request burst, a burst across a two-second fixed-window boundary, steady arrivals, and one noisy client followed by another. Each runs with route-wide and per-client keys against a local token bucket and experimental local fixed window. Both use capacity 10 and nominal refill 5/s. Virtual timestamps make the admission results reproducible. Decision timings include JVM warmup and are not valid throughput claims.

The fixed-window limiter is **experimental** and is not selectable as a live gateway policy. This runner does not exercise Redis, HTTP forwarding, multiple gateway processes, upstream failures, or CPU and memory under load. The separate integration tests and verification scripts cover parts of those behaviors. Run controlled HTTP load and failure experiments before drawing production conclusions.
