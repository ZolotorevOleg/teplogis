#!/usr/bin/env python3
"""Small dependency-free HTTP load check for a prepared LCT import."""
import argparse
import concurrent.futures
import json
import math
import time
import urllib.error
import urllib.request


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1)]


def fetch(url, request_number):
    started = time.perf_counter()
    request = urllib.request.Request(url, headers={"X-Request-ID": "load-%d" % request_number})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
            return response.status, time.perf_counter() - started
    except urllib.error.HTTPError as error:
        error.read()
        return error.code, time.perf_counter() - started
    except Exception:
        return 0, time.perf_counter() - started


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--import-id", required=True)
    parser.add_argument("--users", type=int, default=50)
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--map-limit", type=int, default=2000)
    parser.add_argument("--output")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    endpoints = [
        base + "/actuator/health/readiness",
        base + "/api/v1/imports/" + args.import_id,
        base + "/api/v1/imports/" + args.import_id + "/geometry",
        base + "/api/v1/imports/" + args.import_id + "/geometry/map?after=-1&limit=" + str(args.map_limit),
    ]
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.users) as pool:
        futures = [pool.submit(fetch, endpoints[i % len(endpoints)], i) for i in range(args.requests)]
        results = [future.result() for future in futures]
    elapsed = time.perf_counter() - started
    latencies = [seconds * 1000 for _, seconds in results]
    statuses = {}
    for status, _ in results:
        statuses[str(status)] = statuses.get(str(status), 0) + 1
    report = {
        "users": args.users,
        "requests": args.requests,
        "elapsedSeconds": round(elapsed, 3),
        "requestsPerSecond": round(args.requests / elapsed, 2),
        "statuses": statuses,
        "errors": sum(count for status, count in statuses.items() if not status.startswith("2")),
        "latencyMs": {
            "p50": round(percentile(latencies, .50), 2),
            "p95": round(percentile(latencies, .95), 2),
            "p99": round(percentile(latencies, .99), 2),
            "max": round(max(latencies), 2),
        },
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as target:
            target.write(rendered + "\n")
    raise SystemExit(0 if report["errors"] == 0 else 1)


if __name__ == "__main__":
    main()
