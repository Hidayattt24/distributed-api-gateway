# Release checklist

The repository source is prepared for `0.1.0`, but publishing a GitHub Release and pushing images are separate maintainer actions. Do not claim production readiness; review [SECURITY.md](SECURITY.md) and the checks below first.

1. Check `git status` and review the diff. Confirm tests and the custom-backend example on a clean checkout.
2. On Windows run `.\scripts\package-release.ps1 -Version 0.1.0`. This runs tests, builds the ZIP/JAR, and writes `build/release/0.1.0/SHA256SUMS.txt`. On Linux run `./gradlew -PreleaseVersion=0.1.0 check :gateway:gateway-runtime:distZip :control-plane:bootJar --no-daemon` and compute SHA-256 hashes for the outputs.
3. Run `.\scripts\init-local-secrets.ps1` once, then `docker compose config --quiet`. Build `Dockerfile.gateway` and `Dockerfile.control-plane` with an explicit `0.1.0` tag. Run the verification scripts against the local stack. The CI Compose validation uses dummy values and does not publish them.
4. Check the SHA-256 checksums for the ZIP and JAR. The Windows packaging script generates them; CI also uploads a checksum file.
5. Review [CHANGELOG.md](CHANGELOG.md), [LICENSE](LICENSE), and [SECURITY.md](SECURITY.md). Confirm any third-party notices required by redistributed dependencies and images.
6. After review, create and push tag `v0.1.0`, create a GitHub Release with the ZIP, JAR, checksums and limitations, and publish images only to a chosen registry with a maintainer-owned account.

CI runs on tags and uploads build artifacts for review. A `vX.Y.Z` tag determines the artifact version. CI does not publish a release or registry image automatically. `-PreleaseVersion=x.y.z` changes Gradle artifact names; the local default is `0.1.0`.
