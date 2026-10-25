"""Generate bounded local demo traffic for the provisioned Grafana dashboard."""

import argparse
import collections
import concurrent.futures
import json
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8080/vets")
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--rps", type=int, default=20)
    args = parser.parse_args()
    parsed = urlparse(args.url)
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Demo traffic is restricted to an HTTP loopback address")
    if parsed.path != "/vets" or parsed.query or parsed.fragment or parsed.username or parsed.password:
        parser.error("Demo traffic only supports the /vets route")
    if not 1 <= args.seconds <= 180 or not 1 <= args.rps <= 50:
        parser.error("Use 1..180 seconds and 1..50 requests per second")

    def request():
        try:
            with urllib.request.urlopen(args.url, timeout=5) as response:
                response.read()
                return response.status
        except urllib.error.HTTPError as error:
            error.close()
            return error.code
        except (urllib.error.URLError, TimeoutError):
            return 0

    counts = collections.Counter()
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.rps, 50)) as pool:
        for second in range(args.seconds):
            deadline = started + second
            if time.monotonic() < deadline:
                time.sleep(deadline - time.monotonic())
            futures = [pool.submit(request) for _ in range(args.rps)]
            counts.update(f.result() for f in futures)
            if (second + 1) % 15 == 0:
                print(f"{second + 1}s: {dict(sorted(counts.items()))}", flush=True)
    print(json.dumps({"duration_s": args.seconds, "target_rps": args.rps,
                      "total": sum(counts.values()), "statuses": dict(sorted(counts.items()))}, indent=2))


if __name__ == "__main__":
    main()
