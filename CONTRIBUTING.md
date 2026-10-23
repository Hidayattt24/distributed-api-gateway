# Contributing

This project is experimental infrastructure software. Start with a small issue or pull request and explain the behavior you expect.

## Local checks

- Install JDK 21 and use the Gradle Wrapper: `./gradlew check` (`.\gradlew.bat check` on Windows).
- Run the deterministic limiter comparison with `./gradlew :experiments:run --args='--out results/latest'`.
- If changing Docker or configuration, run `docker compose config --quiet` and the relevant verification script in `scripts/`.
- Include a test for behavior that could regress, especially quota accounting, forwarding, and failures.

Do not commit credentials, private API keys, generated build output, or the local working PRD under `docs/`.

## Pull requests

Describe the change, how it was tested, and any new operational limit. Keep data-plane behavior and control-plane behavior distinct. The project currently accepts HTTP/1.1 only and is not claimed to be production ready.

Report potential vulnerabilities privately using the process in [SECURITY.md](SECURITY.md).
