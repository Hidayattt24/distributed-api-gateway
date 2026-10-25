package dev.distributedgateway.experiments;

import dev.distributedgateway.core.LocalTokenBucketRateLimiter;
import dev.distributedgateway.core.RateLimitPolicy;
import dev.distributedgateway.core.RateLimiter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Repeatable decision-level experiment; deliberately excludes HTTP and Redis. */
public final class LimiterComparison {
    private record Event(long nanos, String client) {}
    private record Result(String scenario, String algorithm, String keyMode, long nanos,
                          String client, boolean allowed, long elapsedNanos) {}

    private LimiterComparison() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0 && (args.length != 2 || !"--out".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: --out <directory>");
        }
        Path output = Path.of(args.length == 0 ? "results/latest" : args[1]).toAbsolutePath();
        Files.createDirectories(output);
        RateLimitPolicy policy = new RateLimitPolicy(10, 5.0);
        List<Result> results = new ArrayList<>();
        for (String scenario : List.of("burst", "boundary", "steady", "skewed")) {
            for (String algorithm : List.of("token-bucket", "fixed-window")) {
                for (String mode : List.of("route", "client")) {
                    AtomicLong clock = new AtomicLong();
                    RateLimiter limiter = "token-bucket".equals(algorithm)
                            ? new LocalTokenBucketRateLimiter(clock::get)
                            : new LocalFixedWindowRateLimiter(clock::get, 10_000, 120_000_000_000L);
                    for (Event event : workload(scenario)) {
                        clock.set(event.nanos());
                        long started = System.nanoTime();
                        boolean allowed = limiter.evaluate("route".equals(mode) ? "demo" : event.client(),
                                policy).block().allowed();
                        results.add(new Result(scenario, algorithm, mode, event.nanos(), event.client(),
                                allowed, System.nanoTime() - started));
                    }
                }
            }
        }
        StringBuilder raw = new StringBuilder("scenario,algorithm,key_mode,at_nanos,client,allowed,decision_nanos\n");
        for (Result r : results) {
            raw.append(r.scenario()).append(',').append(r.algorithm()).append(',').append(r.keyMode())
                    .append(',').append(r.nanos()).append(',').append(r.client()).append(',')
                    .append(r.allowed()).append(',').append(r.elapsedNanos()).append('\n');
        }
        Files.writeString(output.resolve("requests.csv"), raw, StandardCharsets.UTF_8);
        String environment = "generatedAtUtc=" + Instant.now() + "\njava.version=" + System.getProperty("java.version")
                + "\nos.name=" + System.getProperty("os.name") + "\nos.arch=" + System.getProperty("os.arch")
                + "\navailableProcessors=" + Runtime.getRuntime().availableProcessors()
                + "\ncapacity=10\nrefillTokensPerSecond=5.0\nrandomSeed=none\n";
        Files.writeString(output.resolve("environment.properties"), environment, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("report.md"), report(results), StandardCharsets.UTF_8);
        System.out.println("Results: " + output);
    }

    private static List<Event> workload(String scenario) {
        List<Event> events = new ArrayList<>();
        switch (scenario) {
            case "burst" -> {
                for (int i = 0; i < 60; i++) events.add(new Event(0, i % 2 == 0 ? "A" : "B"));
            }
            case "boundary" -> {
                for (int i = 0; i < 10; i++) events.add(new Event(1_900_000_000L, "A"));
                for (int i = 0; i < 10; i++) events.add(new Event(2_000_000_000L, "A"));
            }
            case "steady" -> {
                for (int i = 0; i < 100; i++) events.add(new Event(i * 100_000_000L, i % 2 == 0 ? "A" : "B"));
            }
            case "skewed" -> {
                for (int i = 0; i < 50; i++) events.add(new Event(0, "A"));
                for (int i = 0; i < 10; i++) events.add(new Event(0, "B"));
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        return events;
    }

    private static String report(List<Result> results) {
        StringBuilder out = new StringBuilder("# Rate limiter comparison\n\n"
                + "Hypothesis: with capacity 10 and refill 5/s, a fixed window admits a larger burst "
                + "across its two-second boundary. Per-client keys isolate clients but raise total allowance.\n\n"
                + "Method: deterministic virtual time, sequential decisions, fresh local limiter for every row. "
                + "No HTTP, Redis, concurrency, random input, or real sleeping. "
                + "See requests.csv and environment.properties.\n\n"
                + "| Workload | Algorithm | Key | Allowed | Rejected | Max accepted in 2s | A | B | Mean decision ns* |\n"
                + "|---|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (String scenario : List.of("burst", "boundary", "steady", "skewed")) {
            for (String algorithm : List.of("token-bucket", "fixed-window")) {
                for (String mode : List.of("route", "client")) {
                    List<Result> row = results.stream().filter(r -> r.scenario().equals(scenario)
                            && r.algorithm().equals(algorithm) && r.keyMode().equals(mode)).toList();
                    long allowed = row.stream().filter(Result::allowed).count();
                    long a = row.stream().filter(r -> r.allowed() && r.client().equals("A")).count();
                    long b = row.stream().filter(r -> r.allowed() && r.client().equals("B")).count();
                    long mean = (long) row.stream().mapToLong(Result::elapsedNanos).average().orElse(0);
                    out.append('|').append(scenario).append('|').append(algorithm).append('|').append(mode)
                            .append('|').append(allowed).append('|').append(row.size() - allowed)
                            .append('|').append(maxInTwoSeconds(row)).append('|').append(a).append('|').append(b)
                            .append('|').append(mean).append("|\n");
                }
            }
        }
        return out.append("\n*Decision timing includes JVM and Reactor overhead without warmup; do not use it "
                + "for throughput claims. The boundary row shows fixed-window edge burst; the skewed row "
                + "shows client isolation only when keys are per-client. This experiment does not measure "
                + "Redis correctness, network latency, or gateway throughput.\n").toString();
    }

    private static int maxInTwoSeconds(List<Result> row) {
        List<Long> times = row.stream().filter(Result::allowed).map(Result::nanos).sorted().toList();
        int best = 0;
        for (int left = 0, right = 0; right < times.size(); right++) {
            while (times.get(right) - times.get(left) >= 2_000_000_000L) left++;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
