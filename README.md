# Leonardo

S3-compatible object storage server. JVM-only (Java 21), Micronaut 4, Netty. Single-node, filesystem-backed (ZFS or NFS recommended), with caller-chosen object IDs and YAML metadata.

Apache License 2.0. See `LICENSE` and `NOTICE`.

## Status

Pre-alpha. Milestone M0 (project scaffolding) in progress. See `docs/project-plan.md` for the full roadmap.

## Building

Requires JDK 21.

```sh
./gradlew build
```

## Running locally

```sh
./gradlew :leonardo-app:run
```

Default config is read from `/etc/leonardo/leonardo.yaml`, or an overriding path supplied via `--config=/path/to/leonardo.yaml`. Sample config lives in `config/leonardo.yaml`.

## Module layout

| Module | Purpose |
| --- | --- |
| `leonardo-app` | Micronaut application, `main()`, Netty wiring |
| `leonardo-api` | S3 REST controllers, request/response binding |
| `leonardo-core` | Domain model, services, business logic |
| `leonardo-storage` | Filesystem backend, locking, fsync, on-disk layout |
| `leonardo-auth` | SigV2 + SigV4 verification, API key store |
| `leonardo-yaml` | YAML metadata reader/writer with atomic rename |
| `leonardo-xml` | S3 XML marshalling |
| `leonardo-admin` | Admin endpoints (health, metrics, key reload) |
| `leonardo-cli` | `leonardo-admin` CLI for operator tasks |

## Platform support

Targets MidnightBSD, FreeBSD, and Linux. The JVM gives us identical behavior across all three; OS-specific features (Capsicum on BSD, for example) are guarded by runtime detection.
