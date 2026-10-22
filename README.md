# Distributed API Gateway

Java 21 and Reactor Netty gateway. The gateway matches routes, balances traffic across upstream instances with Round Robin and passive health checks, enforces local or Redis-backed distributed Token Bucket rate limiting, and forwards allowed HTTP requests to backend microservices.

The PetClinic source is kept in a separate local clone of [Spring PetClinic Microservices](https://github.com/spring-petclinic/spring-petclinic-microservices) (upstream commit `1d76b00`, Apache 2.0). This gateway's Compose file uses PetClinic's published service images and a Redis instance for shared quota state. It does not start PetClinic's own API gateway: port 8080 belongs to this project.

## Modules

- `gateway/gateway-core`: route matching, upstream pool load balancing, and Reactor Netty proxy.
- `gateway/gateway-ratelimit`: Redis distributed Token Bucket (atomic Lua), key resolvers, and local fallback.
- `gateway/gateway-runtime`: executable and configuration loader.
- `control-plane`: Spring Boot administration API backed by PostgreSQL; stores immutable configuration versions.
- PetClinic `customers-service`, `visits-service`, and `vets-service`: external upstream services. The cloned source lives outside this Gradle project.

## Run on Windows

Requirements: JDK 21, Docker Desktop, Git. Open this repository root in VS Code or IntelliJ IDEA. Use the included Gradle Wrapper.

```powershell
docker compose up --build -d
docker compose ps
```

The first startup pulls PetClinic images and may take several minutes. Its config server retrieves configuration from the PetClinic config repository. After services are ready, try:

```powershell
curl.exe -i 'http://localhost:8080/owners/1'
curl.exe -i 'http://localhost:8080/vets'
curl.exe -i 'http://localhost:8080/pets/visits?petId=1'
curl.exe -i 'http://localhost:8080/missing'
```

Routes preserve the request path and query string. `/owners` reaches `customers-service:8081`, `/pets/visits` reaches `visits-service:8082`, and `/vets` reaches `vets-service:8083`. A request for another path returns 404 from this gateway. While a PetClinic service is still starting, the gateway may return 502 or 504; retry after `docker compose ps` shows the services running.

Each configured route has a Token Bucket policy: burst 10, refill 5 tokens per second. A burst above this limit returns HTTP 429 from the gateway. Change routes and policies in `config/gateway.properties`; Compose supplies the corresponding upstream URLs with environment variables.

If `REDIS_URL` is set (as in Compose), quota state is shared through Redis instead. Without it, the gateway uses local buckets. For `keyType=API_KEY`, configure `RATE_LIMIT_<ROUTE_ID>_API_KEY_SHA256` in the gateway environment with comma-separated SHA-256 hex digests. Startup fails if no hashes are configured. Missing or invalid `X-API-Key` receives HTTP 401, before quota evaluation. Rotate keys by updating the digest list and restarting the gateway. `CLIENT_IP` uses the direct TCP peer address; behind a load balancer, this is the load balancer IP until a trusted proxy model is added. Local buckets have a strict maximum of 10,000; a new identity receives HTTP 429 if every slot remains active.

For local Java development, start only PetClinic's dependencies, then run the gateway from another terminal:

```powershell
docker compose up -d config-server discovery-server customers-service visits-service vets-service
.\gradlew.bat :gateway:gateway-runtime:run
```

Run automated tests with `.\gradlew.bat test`. Stop the containers with `docker compose down`. The source of PetClinic is in the sibling clone; you do not need to copy it into this gateway's Gradle build.

## Reproducible verification

Run the Redis integration test against a real local Redis. It creates two live gateway instances, sends a concurrent burst through both, and verifies one shared quota. The test is skipped when `REDIS_TEST_URL` is absent.

```powershell
docker compose up -d redis
$env:REDIS_TEST_URL = 'redis://localhost:6379'
.\gradlew.bat test --rerun-tasks
Remove-Item Env:REDIS_TEST_URL
```

With `REDIS_URL` configured, gateway startup fails with a clear error if Redis cannot be reached. Request-time Redis operations have a 2-second timeout, then use each route's configured `LOCAL_FALLBACK` or `FAIL_OPEN` policy. Fallback favors availability and can exceed the shared quota during a Redis outage or a late Redis response. The Redis script uses Redis server time so gateway clock skew does not change the shared bucket. The upstream check is passive: connection and timeout failures start a cooldown; after cooldown, an upstream is tried again. It is not a background health probe.

Run the complete PetClinic demo and verify route responses and a burst rejection:

```powershell
docker compose up --build -d
.\scripts\verify-petclinic.ps1
.\scripts\verify-redis-outage.ps1
docker compose down
```

The proxy tests additionally cover a 1 MiB request/response body, upstream failure and recovery, forwarding, and hop-by-hop headers. Graceful draining of in-flight requests, client cancellation, and production-scale streaming limits are not yet verified.

## M5: change gateway configuration without restarting it

Compose starts the control plane on `http://localhost:9090` and PostgreSQL with a persistent `control-db` volume. On the first startup, the control plane seeds version 1 from `config/gateway.properties`. The gateway polls `/internal/config/active` every two seconds on a background thread and atomically replaces its validated snapshot. Existing requests finish using the snapshot they captured. When the control plane cannot be reached, the gateway keeps its last valid snapshot.

The demo token defaults to `local-demo-token`. Set `CONTROL_PLANE_TOKEN` before `docker compose up --build -d` to choose another token. The administration port binds only to localhost in Compose. The demo database password can be overridden with `CONTROL_DATABASE_PASSWORD`. Change both values before any shared deployment.

Try the complete publish and rollback flow from Windows PowerShell:

```powershell
docker compose up --build -d
.\scripts\verify-control-plane.ps1
```

The script temporarily removes `/vets`, waits until `/_gateway/version` advances, checks that `/vets` returns 404, then publishes a rollback as a new version and checks that `/vets` returns 200. The gateway process is not restarted. To inspect the active configuration and history manually:

```powershell
$headers = @{ Authorization = 'Bearer local-demo-token' }
Invoke-RestMethod http://localhost:9090/admin/config/active -Headers $headers
Invoke-RestMethod http://localhost:9090/admin/config/versions -Headers $headers
Invoke-WebRequest -UseBasicParsing http://localhost:8080/_gateway/version
```

API: `POST /admin/config/versions` accepts `expectedVersion`, `responseTimeoutSeconds`, and a complete `routes` array. `POST /admin/config/rollback/{targetVersion}` accepts `expectedVersion`; rollback creates a new version. A stale expected version returns 409, invalid routes return 400, and missing or invalid bearer tokens return 401. Configurations are immutable once published. Upstream URLs, route prefixes, rate policies, and response timeout can all be changed. The control plane is queried only by the background poller, never for each proxied request.

## M6: observability and reliability

Compose bundles Prometheus (`http://localhost:9091`) and Grafana (`http://localhost:3000`, user/pass: `admin`/`admin`) with a pre-provisioned dashboard:

- **Metrics endpoint:** `GET /_gateway/metrics` exports Prometheus text format covering `gateway_requests_total`, `gateway_request_duration_seconds` (p50/p95/p99 quantiles), `gateway_rate_limited_total`, `gateway_upstream_failures_total`, `gateway_redis_failures_total` (after a Redis request fails), and `gateway_in_flight_requests`.
- **Health endpoint:** `GET /_gateway/health` reports status `UP` (200) or `SERVICE_UNAVAILABLE` (503) based on upstream availability.
- **Structured access logging:** Emits sanitized JSON lines per request with method, path, route, status, duration, upstream, rate limit outcome, and client IP without leaking sensitive authorization credentials.
- **Graceful shutdown:** On SIGTERM/SIGINT, the gateway rejects new requests with 503, waits up to 10 seconds for requests already running, then closes the server and Redis client.

Verify observability and metrics collection:

```powershell
docker compose up --build -d
.\scripts\verify-observability.ps1
```

The working PRD is kept locally in `docs/PRD.md` and excluded from the public repository. This is an experimental engineering project and has not been hardened as a production security boundary. A license for this gateway repo still needs to be selected before publishing it as open source.
