# Distributed API Gateway

Java 21 and Reactor Netty gateway. The gateway matches routes, balances traffic across upstream instances with Round Robin and passive health checks, enforces local or Redis-backed distributed Token Bucket rate limiting, and forwards allowed HTTP requests to backend microservices.

The PetClinic source is kept in a separate sibling repository: a separate local clone of [Spring PetClinic Microservices](https://github.com/spring-petclinic/spring-petclinic-microservices) (upstream commit `1d76b00`, Apache 2.0). This gateway's Compose file uses PetClinic's published service images and a Redis instance for shared quota state. It does not start PetClinic's own API gateway: port 8080 belongs to this project.

## Modules

- `gateway/gateway-core`: route matching, upstream pool load balancing, and Reactor Netty proxy.
- `gateway/gateway-ratelimit`: Redis distributed Token Bucket (atomic Lua), key resolvers, and local fallback.
- `gateway/gateway-runtime`: executable and configuration loader.
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

Each configured route has its own **local** Token Bucket: burst 10, refill 5 tokens per second. A burst above this limit returns HTTP 429 from the gateway. Quotas are per gateway instance, not shared across replicas. Change routes and policies in `config/gateway.properties`; Compose supplies the corresponding upstream URLs with environment variables.

If `REDIS_URL` is set (as in Compose), quota state is shared through Redis instead. Without it, the gateway uses local buckets. For `keyType=API_KEY`, configure `RATE_LIMIT_<ROUTE_ID>_API_KEY_SHA256` in the gateway environment with comma-separated SHA-256 hex digests. Startup fails if no hashes are configured. Missing or invalid `X-API-Key` receives HTTP 401, before quota evaluation. Rotate keys by updating the digest list and restarting the gateway. `CLIENT_IP` uses the direct TCP peer address; behind a load balancer, this is the load balancer IP until a trusted proxy model is added. Local buckets have a strict maximum of 10,000; a new identity receives HTTP 429 if every slot remains active.

For local Java development, start only PetClinic's dependencies, then run the gateway from another terminal:

```powershell
docker compose up -d config-server discovery-server customers-service visits-service vets-service
.\gradlew.bat :gateway:gateway-runtime:run
```

Run automated tests with `.\gradlew.bat test`. Stop the containers with `docker compose down`. The source of PetClinic is in the sibling clone; you do not need to copy it into this gateway's Gradle build.

The working PRD is kept locally in `docs/PRD.md` and excluded from the public repository. This is an experimental engineering project and has not been hardened as a production security boundary. A license for this gateway repo still needs to be selected before publishing it as open source.
