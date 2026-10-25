"""Bounded HTTP load probe for an isolated gateway environment (Python 3.11+)."""

import argparse
import concurrent.futures
import json
import math
import time
import urllib.error
import urllib.request


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("url", help="URL of an isolated test gateway")
    parser.add_argument("--requests", type=int, default=1000)
    parser.add_argument("--concurrency", type=int, default=25)
    parser.add_argument("--api-key", default="")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--max-p95-ms", type=float, required=True)
    parser.add_argument("--max-p99-ms", type=float, required=True)
    parser.add_argument("--max-error-rate", type=float, required=True)
    args = parser.parse_args()
    if not (1 <= args.requests <= 100000 and 1 <= args.concurrency <= 1000):
        parser.error("requests and concurrency must be within bounded ranges")

    headers = {"X-API-Key": args.api_key} if args.api_key else {}

    def probe(_):
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(urllib.request.Request(args.url, headers=headers), timeout=args.timeout) as response:
                response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
        except (urllib.error.URLError, TimeoutError):
            status = 0
        return status, (time.perf_counter() - started) * 1000

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(probe, range(args.requests)))
    elapsed = time.perf_counter() - started
    latencies = [latency for _, latency in results]
    statuses = {str(status): sum(value == status for value, _ in results) for status in sorted({s for s, _ in results})}
    errors = sum(status == 0 or status >= 500 for status, _ in results)
    report = {
        "url": args.url,
        "requests": args.requests,
        "concurrency": args.concurrency,
        "duration_s": round(elapsed, 2),
        "rps": round(args.requests / elapsed, 2),
        "p95_ms": round(percentile(latencies, 0.95), 2),
        "p99_ms": round(percentile(latencies, 0.99), 2),
        "error_rate": round(errors / args.requests, 4),
        "statuses": statuses,
        "thresholds": {
            "p95_ms": args.max_p95_ms,
            "p99_ms": args.max_p99_ms,
            "error_rate": args.max_error_rate,
        },
    }
    report["passed"] = (report["p95_ms"] <= args.max_p95_ms
                        and report["p99_ms"] <= args.max_p99_ms
                        and report["error_rate"] <= args.max_error_rate)
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
