# Distributed API Gateway

An educational open-source gateway project for studying HTTP proxy correctness and, in later milestones, distributed traffic management. **Current scope: Milestone 1, a single gateway, a local Token Bucket, and one example backend.** This repository does not yet include load balancing, Redis rate limiting, a control plane, or production hardening.

Read the [Product Requirements Document](PRD.md) for the full scope, architecture, acceptance criteria, and milestone plan.

## Modules

- `gateway/gateway-core`: route matching, local rate limiting, and HTTP forwarding with Reactor Netty.
- `gateway/gateway-runtime`: executable gateway and local configuration loading.
- `examples/user-service`: independently runnable Spring Boot example backend.

The backend is an example, not a gateway dependency. Any reachable HTTP backend can be used by changing the upstream URL.

### Why only the example service uses Spring Boot

The gateway is deliberately implemented with Reactor Netty directly so this project can study HTTP proxy behavior and non-blocking I/O without a Spring Boot application layer. The example User Service uses Spring Boot 4.1.1 and Spring Web MVC. A future control plane may also use Spring Boot, as described in the project specification.

[Spring Initializr](https://start.spring.io/) is a generator for a new Spring Boot project, not a prerequisite installation. This repository uses one Gradle multi-project build, so the Spring Boot example module was configured directly in `examples/user-service/build.gradle` instead of importing a second generated root project. Gradle resolves the Spring Boot plugin and dependencies during the build.

## Requirements

- JDK 21
- Docker Desktop, only for the Compose option
- Git and an editor such as VS Code or IntelliJ IDEA

The Gradle Wrapper is included, so a separate Gradle installation is unnecessary. Open this **repository root** in your editor and select JDK 21 as the project and Gradle JVM.

## Run locally on Windows

From PowerShell in the repository root, use two terminals:

```powershell
# Terminal 1
.\gradlew.bat :examples:user-service:bootRun
```

```powershell
# Terminal 2
.\gradlew.bat :gateway:gateway-runtime:run
```

If Windows selects a different JDK, set `JAVA_HOME` to your JDK 21 installation in both terminals before running the commands.

## Run with Docker Compose

```powershell
docker compose up --build
```

The gateway listens on port 8080 and the example backend on port 8081. Compose sets `USER_SERVICE_URL` so the gateway reaches the backend by its Compose service name.

## Try the gateway

```powershell
curl.exe -i 'http://localhost:8080/users/123?source=demo'
curl.exe -i -X POST 'http://localhost:8080/users/echo' -H 'Content-Type: text/plain' --data 'hello gateway'
curl.exe -i 'http://localhost:8080/missing'
```

Expected: JSON for the GET request, status `201` and echoed body for POST, and status `404` for an unmatched route. The upstream can be changed in `config/gateway.properties` or with the `USER_SERVICE_URL` environment variable.

The `/users` route has a local Token Bucket. The default policy allows a burst of 10 requests and refills 5 tokens per second **on each gateway instance**. To observe `429`, send more than 10 requests quickly to `/users/123`; the gateway rejects excess requests before contacting the backend. Change `rateLimit.users.enabled`, `rateLimit.users.capacity`, and `rateLimit.users.refillTokensPerSecond` in `config/gateway.properties` to adjust the local policy. Redis is used only when a future distributed policy is implemented and enabled.

Run automated tests:

```powershell
.\gradlew.bat test
```

## Scope and next steps

The initial route configuration is intentionally small: one `/users` prefix and one local rate-limit policy in `config/gateway.properties`. The request flow is route matching, policy selection, local quota evaluation, then upstream forwarding if allowed. The next work is to verify more HTTP edge cases and timeout behavior, then add multiple routes and upstream instances. Shared quotas via Redis are planned for a later milestone.

This is an experimental engineering project. Do not use it as a production security boundary without further protocol, security, and reliability validation.

## License

License selection is an open decision in the project specification. Add a license before publishing the repository as open source.
#