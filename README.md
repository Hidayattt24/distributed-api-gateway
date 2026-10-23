# Distributed API Gateway

Experimental HTTP/1.1 API gateway built with **Java 21** and **Reactor Netty**. It provides routing, local or Redis-backed token bucket rate limiting, passive upstream recovery, versioned configuration and Prometheus/Grafana observability. It has not been hardened or benchmarked for production use.

---

## Table of Contents

- [Overview](#overview)
- [Why an API Gateway? (Architecture Flow)](#why-an-api-gateway-architecture-flow)
  - [Without an API Gateway (Direct Client-to-Service)](#without-an-api-gateway-direct-client-to-service)
  - [With Distributed API Gateway (Centralized Entry Point)](#with-distributed-api-gateway-centralized-entry-point)
- [System Architecture: Data Plane vs. Control Plane](#system-architecture-data-plane-vs-control-plane)
- [Core Features](#core-features)
- [Project Modules](#project-modules)
- [Reference Demo Microservices](#reference-demo-microservices)
- [Quick Start](#quick-start)
- [Configuration Reference](#configuration-reference)
- [Observability & Monitoring](#observability--monitoring)
- [Dynamic Reconfiguration & Rollback](#dynamic-reconfiguration--rollback)
- [Automated Verification Scripts](#automated-verification-scripts)

---

## Overview

Modern microservices often expose multiple HTTP services. This gateway provides one configurable entry point for local experiments with routing and traffic management.

**Distributed API Gateway** solves this by sitting between external clients and backend microservices:
1. **Single Public Entry Point:** Clients communicate with one domain/port; internal services remain private.
2. **Traffic Protection:** Local or Redis-backed token bucket limits requests before they reach a backend.
3. **Resilience & Fault Isolation:** Unhealthy upstream instances are automatically removed from rotation and gradually retried after a cooldown period.
4. **Runtime Updates:** Routes, rate limits, and upstreams can be modified and rolled back through the authenticated Control Plane API without restarting the gateway data plane.

---

## Why an API Gateway? (Architecture Flow)

### Without an API Gateway (Direct Client-to-Service)

When web browsers or mobile apps interact directly with backend microservices:

```text
                                Internet
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         │ :8081                   │ :8082                   │ :8083
         ▼                         ▼                         ▼
┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐
│ Customer Service │      │  Visit Service   │      │   Vet Service    │
│ (Public IP/Port) │      │ (Public IP/Port) │      │ (Public IP/Port) │
└──────────────────┘      └──────────────────┘      └──────────────────┘
```

**Challenges:**
- **Exposed Attack Surface:** Every microservice must expose public ports and public IP addresses.
- **Complex Client Logic:** Clients must track multiple hostnames, ports, and API versions.
- **CORS Overhead:** Every microservice must implement and maintain cross-origin headers, causing preflight `OPTIONS` latency.
- **No Global Rate Limiting:** A burst of requests can saturate backend databases directly.
- **Client-Side Failover:** If an instance crashes, the client experiences immediate failures.

---

### With Distributed API Gateway (Centralized Entry Point)

The gateway acts as an intelligent, high-throughput reverse proxy:

```text
                         [ Web Browser / Mobile App ]
                                      │
                                      │ Single Endpoint: https://api.example.com
                                      │ (GET /owners, /pets/visits, /vets)
                                      ▼
                      ┌───────────────────────────────┐
                      │    DISTRIBUTED API GATEWAY    │
                      │  - HTTP/1.1 forwarding        │
                      │  - Path Prefix Routing        │
                      │  - Redis Token Bucket Limiter │
                      │  - Round Robin Load Balancer  │
                      │  - Hop-by-Hop Header Cleanup  │
                      └───────────────┬───────────────┘
                                      │
   ═══════════════════════════════════╪═════════════════════════════════════════════
   PRIVATE INTERNAL NETWORK (VPC / Docker Network - No Public Access)
   ═══════════════════════════════════╪═════════════════════════════════════════════
                                      │
                  ┌───────────────────┼───────────────────┐
                  ▼                   ▼                   ▼
         ┌──────────────────┐┌──────────────────┐┌──────────────────┐
         │ Customer Service ││  Visit Service   ││   Vet Service    │
         │  (10.0.1.10:8081)││  (10.0.1.11:8082)││  (10.0.1.12:8083)│
         └──────────────────┘└──────────────────┘└──────────────────┘
```

**Benefits:**
- **Deployment choice:** Backends stay on the private Compose network. The demo does not publish PetClinic or Redis ports to the host.
- **Central routing:** Clients can address one gateway endpoint. TLS, CORS and general authentication are outside the current gateway scope.
- **Shielded Microservices:** Requests exceeding quotas are dropped at the perimeter with `HTTP 429 Too Many Requests`.
- **Seamless Failover:** Load balancer automatically bypasses unresponsive nodes without client awareness.

---

## System Architecture: Data Plane vs. Control Plane

The project strictly decouples traffic forwarding from configuration management:

```text
                  [ External Clients ]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────────┐
 │ DATA PLANE (:8080) - Java 21 & Reactor Netty               │
 │                                                            │
 │   Active Snapshot (AtomicReference<Snapshot>)              │
 │   ├── Route Matcher (Prefix resolution)                    │
 │   ├── Identity Resolver (ROUTE, API_KEY, CLIENT_IP)        │
 │   ├── Rate Limiter (Redis Lua / Local fallback)            │
 │   ├── Load Balancer (Round Robin + Passive Cooldown)       │
 │   └── Forwarder (Hop-by-hop sanitization & Streaming)      │
 └─────────────────────────▲──────────────────────────────────┘
                           │
                           │ Polls /internal/config/active (every 2s)
                           │ Replaces snapshot atomically without dropping traffic
                           │
 ┌─────────────────────────┴──────────────────────────────────┐
 │ CONTROL PLANE (:9090) - Spring Boot & PostgreSQL           │
 │                                                            │
 │   ├── Admin REST API (/admin/config/...)                   │
 │   ├── Token Authentication (Bearer token)                  │
 │   ├── Config Store (Immutable versioning & Rollback)       │
 │   └── Validation Engine (Route prefixes, URLs, policies)   │
 └────────────────────────────────────────────────────────────┘
```

- **Data Plane:** Built with Reactor Netty. Forwarding uses non-blocking I/O; throughput and latency require workload-specific measurement.
- **Control Plane:** Built with Spring Boot and PostgreSQL. It validates and versions changes, while gateways poll the active snapshot.

---

## Core Features

- **High-Throughput Reactive Proxy:** Pure Netty HTTP client/server pipeline handling streaming payloads and preserving non-hop-by-hop headers.
- **Distributed Token Bucket Rate Limiting:** Evaluates token consumption and refills atomically in Redis using Lua scripts. Supports dynamic Redis key expiration (TTL) to avoid memory leaks.
- **Resilient Fallback Modes:** If Redis experiences downtime, routes automatically degrade to in-memory local token buckets (`LOCAL_FALLBACK`) or allow traffic through (`FAIL_OPEN`).
- **Flexible Identity Resolution:**
  - `ROUTE`: Shared rate limit quota across all callers of a route.
  - `API_KEY`: Per-consumer quota identified by header `X-API-Key`, verified using constant-time SHA-256 digest matching (`ApiKeyVerifier`).
  - `CLIENT_IP`: Per-origin quota using peer network address.
- **Round-Robin Load Balancing:** Distributes requests evenly across upstream pools.
- **Passive Failure Detection:** Unresponsive or timed-out upstreams are isolated into a 10-second cooldown period, then gradually re-introduced upon recovery.
- **Standard HTTP Rate Limit Headers:** Emits `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` (on 429).
- **Request Draining:** On `SIGTERM`/`SIGINT`, the gateway rejects new requests with 503, waits up to 10 seconds for in-flight requests, then closes the server and Redis client.
- **Comprehensive Observability:** Prometheus metrics (`/_gateway/metrics`), health status probe (`/_gateway/health`), and structured JSON access logs.

---

## Project Modules

```text
distributed-api-gateway/
├── gateway/
│   ├── gateway-core/           # Routing, proxy pipeline, load balancing, metrics, and local limiter
│   ├── gateway-ratelimit/      # Redis distributed Token Bucket (Lettuce driver & atomic Lua script)
│   └── gateway-runtime/        # Executable binary, configuration loader, and background subscriber
├── control-plane/              # Spring Boot management API with PostgreSQL persistence
├── deploy/
│   ├── prometheus/             # Prometheus scraping configuration
│   └── grafana/                # Pre-provisioned dashboards and datasources
├── scripts/                    # Automated end-to-end and chaos test scripts
├── config/                     # Default static gateway configuration
└── compose.yaml                # Full microservices, control plane, redis, and monitoring stack
```

---

## Reference Demo Microservices

This repository uses a reference set of backend microservices based on the [Spring PetClinic Microservices](https://github.com/spring-petclinic/spring-petclinic-microservices) architecture:
- `customers-service` (`:8081`): Manages pet owners and pet records.
- `visits-service` (`:8082`): Manages clinic visit histories.
- `vets-service` (`:8083`): Manages veterinarian registries and specialties.

*Note: These backend services serve solely as a test fixture to demonstrate real routing, failover, and rate limiting behavior. The gateway is completely backend-agnostic and works with any HTTP service (Java, Node.js, Go, Python, etc.).*

---

## Quick Start

### Prerequisites
- **JDK 21** or later
- **Docker Desktop** (with Docker Compose v2)
- **Git**

### 1. Launch the Complete Stack

Start the gateway, reference microservices, Redis, PostgreSQL, Control Plane, Prometheus, and Grafana:

```powershell
.\scripts\init-local-secrets.ps1
docker compose up --build -d
docker compose ps
```

The setup script generates random local credentials in an ignored `.env` file. Keep it private. Redis and the PetClinic services stay inside the Compose network. Gateway management, Prometheus and Grafana host ports bind only to `127.0.0.1`. Prometheus accesses management port `9092` inside the Compose network.

*(Note: The first launch pulls container images and initializes the demo services; this may take 1-3 minutes).*

### 2. Send Requests Through the Gateway

All client traffic enters through the Gateway at `http://localhost:8080`:

```powershell
# Proxied to customers-service (:8081)
curl.exe -i http://localhost:8080/owners/1

# Proxied to vets-service (:8083)
curl.exe -i http://localhost:8080/vets

# Proxied to visits-service (:8082)
curl.exe -i 'http://localhost:8080/pets/visits?petId=1'

# Non-existent route returns 404 from the Gateway
curl.exe -i http://localhost:8080/unknown-path
```

### 3. Observe Rate Limiting

By default, routes are configured with a burst capacity of 10 tokens and a refill rate of 5 tokens/second. Sending a rapid burst will result in HTTP `429 Too Many Requests`:

```powershell
# Send 60 concurrent requests to observe rate limiting in action
$client = [System.Net.Http.HttpClient]::new()
$tasks = 1..60 | ForEach-Object { $client.GetAsync("http://localhost:8080/vets") }
[System.Threading.Tasks.Task]::WaitAll($tasks)
$tasks | ForEach-Object { [int]$_.Result.StatusCode } | Group-Object | Select-Object Name, Count
$client.Dispose()
```

---

## Configuration Reference

Initial configuration can be set in `config/gateway.properties` with supported environment overrides. After the control plane database has been seeded, change active policies through its API rather than editing this file:

```properties
# Gateway server settings
gateway.port=8080
gateway.response-timeout-seconds=5

# Route list
routes=customers,visits,vets

# Route definitions
route.customers.prefix=/owners
route.customers.upstream=http://customers-service:8081

route.vets.prefix=/vets
route.vets.upstream=http://vets-service:8083,http://vets-service-backup:8083

# Rate limit policies
rateLimit.vets.enabled=true
rateLimit.vets.capacity=10
rateLimit.vets.refillTokensPerSecond=5
rateLimit.vets.keyType=ROUTE
rateLimit.vets.failureMode=LOCAL_FALLBACK

# Distributed Redis state (optional; uses in-memory limiter when omitted)
redis.url=redis://redis:6379
```

### Supported Identity Key Types
| Key Type | Description |
|---|---|
| `ROUTE` | Global route-level bucket. All callers share the token pool. |
| `API_KEY` | Per-key bucket identified by `X-API-Key` header. Keys are validated against SHA-256 digests. |
| `CLIENT_IP` | Per-IP bucket based on peer TCP address. |

### Redis Failure Modes
| Mode | Behavior when Redis is unavailable |
|---|---|
| `LOCAL_FALLBACK` *(Default)* | Automatically switches to an in-memory Token Bucket to maintain traffic protection. |
| `FAIL_OPEN` | Passes requests through without rate limit enforcement to prioritize availability. |

---

## Observability & Monitoring

The stack comes out-of-the-box with full observability:

- **Grafana Dashboard:** `http://localhost:3000` (user `admin`; password in the ignored `.env` file)
  - Real-time RPS (Requests Per Second) by route and status code.
  - Request duration quantiles (p50, p95, p99).
  - Rate limiting drop rate (HTTP 429).
  - Upstream failure rate (HTTP 502/504).
  - In-flight request count and active configuration version.
- **Prometheus Targets:** `http://localhost:9091`
- **Gateway Health Probe:** `http://localhost:9092/_gateway/health`
  - Returns `200 OK` when all upstreams are healthy, or `503 Service Unavailable` if an upstream is in cooldown.
- **Gateway Prometheus Metrics:** `http://localhost:9092/_gateway/metrics`

The public gateway port (`8080`) returns 404 for `/_gateway/*`. Outside Compose, management binds to `127.0.0.1` by default. Requests larger than 1 MiB receive 413; more than 256 active requests receive 503. For routes using `API_KEY`, the gateway verifies `X-API-Key` and removes it before forwarding to the microservice.
- **Structured JSON Logging:** Every proxied request produces a clean, sanitized JSON line:
  ```json
  {"timestamp":"2026-10-22T15:30:00.123Z","method":"GET","path":"/vets","route":"vets","status":200,"durationMs":14.20,"upstream":"http://vets-service:8083","rateLimited":false,"clientIp":"172.20.0.1"}
  ```

---

## Dynamic Reconfiguration & Rollback

The Control Plane (`http://localhost:9090`) enables updating gateway routing tables and rate limit policies at runtime without restarting gateway instances:

```powershell
$secrets = Get-Content .env | ConvertFrom-StringData
$env:CONTROL_PLANE_TOKEN = $secrets.CONTROL_PLANE_TOKEN
$headers = @{ Authorization = "Bearer $env:CONTROL_PLANE_TOKEN" }

# 1. Inspect active configuration
Invoke-RestMethod http://localhost:9090/admin/config/active -Headers $headers

# 2. Publish a new configuration version
$body = @{
    expectedVersion = 1
    responseTimeoutSeconds = 5
    routes = @(
        @{
            id = "customers"
            pathPrefix = "/owners"
            upstreams = @("http://customers-service:8081")
        }
    )
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Post -Uri http://localhost:9090/admin/config/versions `
    -Headers $headers -ContentType 'application/json' -Body $body

# 3. Gateway updates its active snapshot in real time:
Invoke-WebRequest -UseBasicParsing http://localhost:9092/_gateway/version
```

---

## Automated Verification Scripts

The repository includes automated PowerShell scripts in the `scripts/` directory for regression and chaos testing:

```powershell
# 1. End-to-end PetClinic routing and burst validation
.\scripts\verify-petclinic.ps1

# 2. Redis outage chaos test (validates automatic fallback during Redis failure)
.\scripts\verify-redis-outage.ps1

# 3. Dynamic route publishing, removal, and rollback validation
.\scripts\verify-control-plane.ps1

# 4. Observability and Prometheus metrics pipeline validation
.\scripts\verify-observability.ps1
```

---

## Running Automated Tests

To run the complete test suite locally (including unit, integration, and fault injection tests):

```powershell
.\gradlew.bat check
```

To run tests with task re-execution:

```powershell
.\gradlew.bat test --rerun-tasks
```

## Experiments and release preparation

- [M7 comparison](experiments/README.md): deterministic token bucket versus fixed-window baseline, with [recorded results](experiments/baseline/report.md) and raw CSV. Fixed window is an experiment, not a live policy.
- [Custom backend example](examples/custom-backend/README.md): run the gateway against a Python HTTP service instead of PetClinic.
- [Release guide](RELEASING.md), [changelog](CHANGELOG.md), [contributing guide](CONTRIBUTING.md), [security policy](SECURITY.md), and [Apache-2.0 license](LICENSE).

Version `0.1.0` source is prepared locally. The gateway ZIP includes `LICENSE` and starter `config/gateway.properties`. CI builds tests, distribution archives and images; no GitHub Release or registry image has been published. Read [SECURITY.md](SECURITY.md) and verify the release checklist before considering any public deployment.
