# Leonardo

S3-compatible object storage server. JVM-only (Java 21), Micronaut 4, Netty. Single-node, filesystem-backed (ZFS or NFS recommended), with caller-chosen object IDs and YAML metadata.

Apache License 2.0. See `LICENSE` and `NOTICE`.

NOTE: Part of this code is AI generated

## Status

Active development. See `docs/project-plan.md` for the full roadmap.

| Milestone | Description | Status |
| --- | --- | --- |
| **M0** | Repo, build, CI skeleton, health endpoint | Done |
| **M1** | SigV2 + SigV4 auth, API key loader, presigned URLs | Done |
| **M2** | Phase 1 — bucket + object CRUD, atomic YAML metadata, caller-chosen object IDs, fsync-rename storage | Done |
| **M3** | Phase 2 — multipart upload, integrity (CRC32C, SHA-256) | Done |
| **M4** | Phase 3 — bucket config (ACL, CORS, tagging, versioning, website, lifecycle, policy, public-access block, request payment) | Done |
| **M5** | Phase 4+5 — object config (ACL, tagging, legal hold, retention), `ListObjectVersions`, object lock configuration | Done |
| **M6** | Conformance pass — aws-cli, rclone, s3fs-fuse, mc, Terraform; Range requests (206), conditional requests (RFC 7232), aws-chunked decode, SigV4 presigned URLs, response-header filter | Done |
| **M7** | Versioning enforcement, WORM/retention enforcement, bucket immutability, `?versionId` routing, virtual-host-style addressing | In progress |
| **M8** | Phase 6 — notifications, server-access logging, replication stubs | Planned |
| **M9** | Phase 7 — encryption (SSE-S3), ownership controls | Planned |
| **M10** | Phase 8–11 long tail | Planned |
| **M11** | MidnightBSD mport, FreeBSD port, Linux packages | Planned |
| **M12** | Multi-region routing | Post-1.0 |

### What works today (M6)

- **Full auth**: SigV4 (header + presigned URL) and SigV2 against a YAML API-key store.
- **Core S3 operations**: `CreateBucket`, `DeleteBucket`, `HeadBucket`, `ListBuckets`, `GetBucketLocation`, `PutObject`, `GetObject`, `HeadObject`, `DeleteObject`, `DeleteObjects`, `CopyObject`, `ListObjects`, `ListObjectsV2`.
- **Multipart**: `CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`, `CompleteMultipartUpload`, `AbortMultipartUpload`, `ListMultipartUploads`, `ListParts`, `GetObjectAttributes`.
- **Bucket config**: ACL, CORS, tagging, versioning, website, lifecycle, bucket policy, public-access block, request payment, object-lock configuration.
- **Object config**: ACL, tagging, legal hold, retention. `ListObjectVersions`.
- **Conformance**: Range/conditional GET, aws-chunked streaming, `x-amz-request-id` / `x-amz-id-2` on every response, `Accept-Ranges`.
- **Storage**: YAML metadata with atomic fsync-rename writes, striped per-object read-write locks, caller-chosen object IDs (`x-leonardo-object-id` header).
- **Security**: XXE-safe XML parsing throughout; POSIX 0600/0700 file modes; SigV4 canonical-request verification.

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
