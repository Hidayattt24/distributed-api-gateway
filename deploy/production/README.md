# Production deployment template

This is a deployment template, not evidence of a production release. Build and scan both images in CI, then set `GATEWAY_IMAGE` and `CONTROL_PLANE_IMAGE` to immutable digests from that same commit. Put real TLS certificates in `certs/`.

## Environment files

- The repository-root `.env.example` lists every variable needed by the **local PetClinic demo**. The ignored root `.env` holds the actual local PostgreSQL, control-plane, and Grafana passwords. `scripts/init-local-secrets.ps1` generates them.
- This directory's `.env.example` is **public** and lists every production variable, including the names of secret variables. Their values are blank; it contains no real passwords, tokens, or API keys.
- This directory's `.env` is **private and Git-ignored**. From the repository root, run `./scripts/init-production-env.ps1` in PowerShell. It copies the public settings and generates a control-plane token plus a raw API key and its SHA-256 hash. The `API_KEY_SECRET` entry is kept only so an operator can retrieve the key; Compose does not send it to the gateway. Never place the raw key in browser JavaScript.

Edit `deploy/production/.env` before deployment: set real image digests and private service addresses, and fill the blank `CONTROL_DATABASE_PASSWORD` with the password already configured on the external PostgreSQL service. If Redis requires credentials, put its authenticated URI in **this private file**. The initializer will not replace an existing `.env` or rotate secrets.

Run production Compose from the repository root with an explicit env file so it cannot accidentally read the local demo credentials:

```powershell
docker compose --env-file deploy/production/.env -f deploy/production/compose.yaml config --quiet
docker compose --env-file deploy/production/.env -f deploy/production/compose.yaml up -d
```

Do not share `docker compose config` output: it includes interpolated environment values. This template still needs real infrastructure and the release checks below.

The Compose file publishes only NGINX ports 80/443. Gateway A/B share an ingress network with NGINX; the control plane and gateways share a backend network. Attach the actual API service to the backend network, or use a privately reachable service URL. Keep PostgreSQL, Redis Sentinel, control-plane port 9090, gateway port 8080, and management port 9092 blocked by host/cloud firewalls. Docker network membership alone is not a substitute for those ACLs. If a CDN or another load balancer is in front of NGINX, configure its trusted-address rules before using its forwarded client IP; this template treats the direct client of NGINX as the client.

`GATEWAY_TRUSTED_PROXY_IPS` is the fixed ingress address `172.28.10.2`. NGINX overwrites `X-Real-IP` with `$remote_addr`; the gateway rejects connections from any other peer. Use an API key on `/api` (`X-API-Key`) and store its SHA-256 hash in `RATE_LIMIT_API_API_KEY_SHA256`. Replace this scheme with your organization's identity provider if you need user-level authorization. `GATEWAY_ALLOWED_UPSTREAMS` is independent of published route configuration and must contain exact scheme, host, and port origins. DNS and network egress policies must also prevent upstream addresses from reaching metadata or control endpoints.

The `/api` sample uses `FAIL_CLOSED`: when Redis cannot make a quota decision, gateway returns 503 and `Retry-After`, rather than issuing a new quota per instance. Configure a privately reachable HA Redis endpoint or Sentinel URI and test promotion with both gateway replicas. The URI format is documented by [Lettuce](https://redis.github.io/lettuce/user-guide/connecting-redis/). Use a managed HA PostgreSQL endpoint and verify its backup and restore procedures separately.

## Release checks

1. On a clean commit, run `./gradlew check` and CI image and dependency scans. Record the commit SHA, artifact checksums, image digests, and scan results together.
2. Before load testing, write p95/p99, error rate, CPU, memory, and recovery targets for your actual workload. Run `python experiments/load_gateway.py https://test.example/api --requests 1000 --concurrency 25 --api-key SECRET --max-p95-ms 250 --max-p99-ms 500 --max-error-rate 0.01` against an isolated test deployment. Inspect HTTP status counts: 429 is expected only for a deliberate quota-exhaustion test. Record `docker stats` and Prometheus results at the same time.
3. Exercise normal traffic, bursts, slow clients, chunked uploads, large responses, and failed upstreams. Current gateway limits are 1 MiB request body and 256 active requests **per gateway**, so choose test cases and limits to match your API. Keep the test report under `experiments/results/`.
4. Verify `curl -I http://HOST/api` returns 308 to HTTPS; `curl -k -i https://HOST/api` without API key returns 401; a valid key reaches the API. A forged `X-Real-IP` or `X-Forwarded-For` sent to NGINX must not change the bucket. Attempt a direct connection to gateway/control-plane/Redis/Postgres from an external host; it must fail.
5. Publish a route outside `GATEWAY_ALLOWED_UPSTREAMS` and expect HTTP 400. Shut off Redis in the isolated environment: both gateways must return 503 on the protected route; restore Redis and confirm healthy requests resume. Confirm 429, 5xx, and Redis alerts reach your notification receiver.
6. Restore a PostgreSQL backup into a fresh isolated database and compare the active version and routes. Test rollback using the control-plane API, then recheck both gateway replicas. Record recovery time. Do not restore into the live database for a test.

The demo `compose.yaml` at the repository root is for local development and still publishes port 8080. Use this template for the separate ingress design.
