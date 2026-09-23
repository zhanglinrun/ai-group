#!/usr/bin/env python3
"""Load-test Group marketing-config reads; metrics aligned with campus-dash S3.

Hit rate = cacheHitCount / requestCount (request-level).
回源降 = dbLoadCount(cache-off) vs dbLoadCount(cache-on).
Latency = client RTT of the config probe.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import statistics
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from urllib import error, request


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def make_jwt(secret: str, user_id: int) -> str:
    now = int(time.time())
    header = b64url(b'{"alg":"HS256","typ":"JWT"}')
    payload = b64url(json.dumps({
        "sub": str(user_id),
        "iss": "ai-group-gateway",
        "aud": ["ai-group-internal"],
        "iat": now,
        "exp": now + 300,
        "jti": str(uuid.uuid4()),
        "username": "cache-bench",
        "role": "USER",
    }, separators=(",", ":")).encode("utf-8"))
    key = hashlib.sha256(secret.encode("utf-8")).digest()
    sig = b64url(hmac.new(key, f"{header}.{payload}".encode("utf-8"), hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


def http_json(method: str, url: str, headers: dict[str, str], body: dict | None = None, timeout: float = 30.0):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = request.Request(url, data=data, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            return resp.status, json.loads(raw) if raw else {}, elapsed_ms, None
    except error.HTTPError as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        try:
            payload = json.loads(exc.read().decode("utf-8"))
        except Exception:
            payload = {}
        return exc.code, payload, elapsed_ms, str(exc)
    except Exception as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return 0, {}, elapsed_ms, str(exc)


def percentile(sorted_vals: list[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def update_dcc(base: str, token: str, key: str, value: str) -> None:
    status, payload, _, err = http_json(
        "POST",
        f"{base}/api/v1/gbm/dcc/update_config?key={key}&value={value}",
        {"Content-Type": "application/json", "X-Internal-Token": token},
    )
    code = str(payload.get("code", ""))
    if status != 200 or (code and code not in ("0000", "0")):
        raise RuntimeError(f"failed to set {key}={value}: status={status} body={payload} err={err}")


def set_cache_switch(base: str, token: str, enabled: bool) -> None:
    # cacheSwitch "0" => cache open; "1" => cache closed (see DCCService.isCacheOpenSwitch).
    update_dcc(base, token, "cacheSwitch", "0" if enabled else "1")


def set_rate_limiter(base: str, token: str, enabled: bool) -> None:
    update_dcc(base, token, "rateLimiterSwitch", "open" if enabled else "close")


def cache_stats(base: str, token: str, reset: bool = False) -> dict:
    status, payload, _, err = http_json(
        "GET",
        f"{base}/api/v1/gbm/index/market_config_cache_stats?reset={'true' if reset else 'false'}",
        {"X-Internal-Token": token},
    )
    if status != 200:
        raise RuntimeError(f"cache stats failed: status={status} err={err} body={payload}")
    return payload.get("data") or {}


def build_identity_pool(secret: str, size: int) -> list[tuple[int, str]]:
    pool: list[tuple[int, str]] = []
    for i in range(max(1, size)):
        user_id = 9_100_000_000_000 + i
        pool.append((user_id, make_jwt(secret, user_id)))
    return pool


def one_request(base: str, token: str, goods_id: str, source: str, channel: str,
                user_id: int) -> tuple[bool, float, str | None]:
    # Client RTT — campus-dash CacheLoadClient style; probe covers config + hall detail caches.
    status, payload, elapsed_ms, err = http_json(
        "GET",
        f"{base}/api/v1/gbm/index/market_config_cache_probe"
        f"?goodsId={goods_id}&source={source}&channel={channel}&userId={user_id}",
        {"X-Internal-Token": token},
    )
    ok = status == 200 and str(payload.get("code", "")) in ("0000", "0")
    return ok, elapsed_ms, None if ok else (err or json.dumps(payload, ensure_ascii=False)[:200])


def run_phase(name: str, base: str, token: str, goods_ids: list[str], source: str, channel: str,
              concurrency: int, total: int, identities: list[tuple[int, str]]) -> dict:
    latencies: list[float] = []
    lock = threading.Lock()
    counter = {"n": 0}
    err_box = {"n": 0}
    n_goods = max(1, len(goods_ids))
    pool_size = max(1, len(identities))

    def worker(worker_idx: int) -> None:
        user_id, _jwt = identities[worker_idx % pool_size]
        while True:
            with lock:
                if counter["n"] >= total:
                    return
                seq = counter["n"]
                counter["n"] += 1
            goods_id = goods_ids[seq % n_goods]
            ok, ms, _ = one_request(base, token, goods_id, source, channel, user_id)
            with lock:
                latencies.append(ms)
                if not ok:
                    err_box["n"] += 1

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(worker, i) for i in range(concurrency)]
        for fut in as_completed(futures):
            fut.result()
    elapsed = time.perf_counter() - started
    latencies.sort()
    ok_count = len(latencies) - err_box["n"]
    return {
        "name": name,
        "concurrency": concurrency,
        "requests": len(latencies),
        "success": ok_count,
        "errors": err_box["n"],
        "elapsedSec": round(elapsed, 3),
        "throughputRps": round(len(latencies) / elapsed, 2) if elapsed > 0 else 0.0,
        "avgMs": round(statistics.fmean(latencies), 2) if latencies else 0.0,
        "p50Ms": round(percentile(latencies, 50), 2),
        "p95Ms": round(percentile(latencies, 95), 2),
        "p99Ms": round(percentile(latencies, 99), 2),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8091")
    parser.add_argument(
        "--goods-ids",
        default="9890002,9890003,9890004",
        help="Comma-separated goods id set to rotate (campus-dash uses a multi-id read set)",
    )
    parser.add_argument("--source", default="s01")
    parser.add_argument("--channel", default="c01")
    parser.add_argument("--concurrency", type=int, default=300)
    parser.add_argument("--requests", type=int, default=15820)
    parser.add_argument("--warmup", type=int, default=800)
    args = parser.parse_args()

    secret = os.environ.get("AI_GROUP_IDENTITY_SIGNING_SECRET", "")
    token = os.environ.get("AI_GROUP_INTERNAL_TOKEN", "")
    if not secret or not token:
        raise SystemExit("AI_GROUP_IDENTITY_SIGNING_SECRET and AI_GROUP_INTERNAL_TOKEN are required")

    goods_ids = [g.strip() for g in args.goods_ids.split(",") if g.strip()]
    if not goods_ids:
        raise SystemExit("--goods-ids must not be empty")

    base = args.base_url.rstrip("/")
    report_root = Path(__file__).resolve().parents[1] / "reports" / "market-config-cache"
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out_dir = report_root / run_id
    out_dir.mkdir(parents=True, exist_ok=True)

    # Identity pool reuses users so owner-team cache can hit (campus-dash multi-id read set).
    identities = build_identity_pool(secret, args.concurrency)

    set_rate_limiter(base, token, enabled=False)
    try:
        set_cache_switch(base, token, enabled=False)
        time.sleep(0.5)
        cache_stats(base, token, reset=True)
        run_phase("warmup-off", base, token, goods_ids, args.source, args.channel,
                  min(50, args.concurrency), args.warmup, identities)
        cache_stats(base, token, reset=True)
        off = run_phase("cache-off", base, token, goods_ids, args.source, args.channel,
                        args.concurrency, args.requests, identities)
        off_stats = cache_stats(base, token, reset=True)

        set_cache_switch(base, token, enabled=True)
        time.sleep(0.5)
        cache_stats(base, token, reset=True)
        run_phase("warmup-on", base, token, goods_ids, args.source, args.channel,
                  min(50, args.concurrency), args.warmup, identities)
        cache_stats(base, token, reset=True)
        on = run_phase("cache-on", base, token, goods_ids, args.source, args.channel,
                       args.concurrency, args.requests, identities)
        on_stats = cache_stats(base, token, reset=False)

        set_cache_switch(base, token, enabled=True)
    finally:
        set_rate_limiter(base, token, enabled=True)

    db_off = int(off_stats.get("dbLoadCount") or 0)
    db_on = int(on_stats.get("dbLoadCount") or 0)
    db_reduction = 0.0 if db_off <= 0 else max(0.0, (db_off - db_on) / db_off)
    hit_rate = float(on_stats.get("hitRate") or 0.0)
    p99_off = off["p99Ms"]
    p99_on = on["p99Ms"]

    report = {
        "runId": run_id,
        "baseUrl": base,
        "goodsIds": goods_ids,
        "concurrency": args.concurrency,
        "requestsPerPhase": args.requests,
        "metricStyle": "campus-dash-S3",
        "cacheOff": off,
        "cacheOn": on,
        "cacheOffStats": off_stats,
        "cacheOnStats": on_stats,
        "dbLoadOff": db_off,
        "dbLoadOn": db_on,
        "dbLoadReduction": round(db_reduction, 4),
        "hitRate": round(hit_rate, 4),
        "resumeLine": (
            f"在 {args.concurrency} 并发下 {on['requests']} 次请求 "
            f"{on['errors']} 异常、平均响应时间 {on['avgMs']}ms、吞吐量 {on['throughputRps']}rps。"
            f"缓存命中率 {round(hit_rate * 100, 1)}%、"
            f"详情回源 {db_off}→{db_on} 次（降 {round(db_reduction * 100, 1)}%）、"
            f"P99 {p99_off}→{p99_on}ms"
        ),
        "endpoint": "market_config_cache_probe(hall+config)",
    }
    (out_dir / "summary.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"REPORT={out_dir / 'summary.json'}")
    return 0 if off["errors"] == 0 and on["errors"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
