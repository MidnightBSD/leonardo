# Leonardo

S3-compatible object storage server. JVM-only (Java 21), Micronaut 4, Netty. Single-node, filesystem-backed (ZFS or NFS recommended), with caller-chosen object IDs and YAML metadata.

Apache License 2.0. See `LICENSE` and `NOTICE`.

NOTE: Part of this code is AI generated

## Status

**v0.1.0 — feature complete.** All planned API milestones shipped. 95 conformance tests passing on MidnightBSD, FreeBSD, and Linux.

| Milestone | Description | Status |
| --- | --- | --- |
| **M0** | Repo, build, CI skeleton, health endpoint | Done |
| **M1** | SigV2 + SigV4 auth, API key loader, presigned URLs | Done |
| **M2** | Core bucket + object CRUD, multipart, `ListObjects` | Done |
| **M3** | ACL, CORS, tagging, versioning, website, lifecycle | Done |
| **M4** | Object-lock, public-access-block, bucket policy | Done |
| **M5** | Presigned URLs, conditional GET, range requests, object attributes | Done |
| **M6** | Request-payer, rate-limit stubs, object tagging, object-level ACL | Done |
| **M7** | Notifications, server-access logging, replication (stubs) | Done |
| **M8** | Encryption, ownership controls, accelerate (stubs) | Done |
| **M9** | Analytics, metrics, inventory, intelligent-tiering, ABAC, metadata table, CreateSession, SelectObjectContent/GetObjectTorrent/WriteGetObjectResponse (501 stubs) | Done |
| **M10** | MidnightBSD mport, FreeBSD port, Linux packages, release workflow | Done |
| **M11** | Multi-region routing | Post-1.0 |

### What's implemented

- **Full auth**: SigV4 (header + presigned URL) and SigV2 against a YAML API-key store.
- **Core S3 operations**: `CreateBucket`, `DeleteBucket`, `HeadBucket`, `ListBuckets`, `GetBucketLocation`, `ListDirectoryBuckets`, `PutObject`, `GetObject`, `HeadObject`, `DeleteObject`, `DeleteObjects`, `CopyObject`, `ListObjects`, `ListObjectsV2`.
- **Multipart**: `CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`, `CompleteMultipartUpload`, `AbortMultipartUpload`, `ListMultipartUploads`, `ListParts`, `GetObjectAttributes`.
- **Bucket config**: ACL, CORS, tagging, versioning, website, lifecycle, bucket policy, public-access block, request payment, object-lock, server-side encryption, ownership controls, accelerate, notifications, server-access logging, replication, analytics, metrics, inventory, intelligent-tiering, ABAC, metadata table configuration.
- **Object config**: ACL, tagging, legal hold, retention. `ListObjectVersions`.
- **Sessions**: `CreateSession` (stub credentials for S3 Express clients).
- **Conformance**: Range/conditional GET, aws-chunked streaming, `x-amz-request-id` / `x-amz-id-2` on every response, `Accept-Ranges`.
- **Storage**: YAML metadata with atomic fsync-rename writes, striped per-object read-write locks, caller-chosen object IDs (`x-leonardo-object-id` header).
- **Security**: XXE-safe XML parsing (including DOM-based output); POSIX 0600/0700 file modes; SigV4 canonical-request verification.
- **501 stubs**: `SelectObjectContent`, `GetObjectTorrent`, `WriteGetObjectResponse` — return `NotImplemented` until demand warrants the work.

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

## Installation

Pre-built archives for each release are attached to the [GitHub releases page](https://github.com/MidnightBSD/leonardo/releases). Download the zip or tar, verify with the provided checksums file, then follow the instructions for your platform in `packaging/`.

| Platform | Packaging |
| --- | --- |
| MidnightBSD | `packaging/midnightbsd/` — mport Makefile + rc.d script |
| FreeBSD | `packaging/freebsd/` — ports Makefile + rc.d script |
| Linux (RPM) | `packaging/linux/leonardo.spec` |
| Linux (DEB) | `packaging/linux/debian/` |
| Linux (any) | `packaging/linux/leonardo.service` — systemd unit |

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
