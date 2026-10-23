# Security policy

This is an experimental project, not a production security boundary. The local Compose setup uses random credentials in an ignored `.env` file. Redis and demo backends are not published on host ports; management, Prometheus and Grafana host ports bind to loopback. Public deployment still requires TLS termination, an upstream allowlist, production secret management, and a trusted client-identity model. The gateway currently caps request bodies at 1 MiB and active requests at 256; review these fixed limits against the intended workload before deployment.

Please do not publish exploit details in a public issue. Use GitHub's private vulnerability reporting for this repository if it is enabled. If that option is unavailable, open a minimal issue asking the maintainer for a private contact channel without including exploit details.

Security fixes are prioritized for the current `0.x` line; there is no long-term support commitment yet.
