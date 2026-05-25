# Leonardo — Project Plan

An S3-compatible object storage API server, written in Java 21 on Micronaut 4.x with Netty. Inspired by early MinIO: a single self-contained binary that turns a POSIX filesystem into an S3 endpoint, without erasure coding, distributed consensus, or vendor lock-in.

---

## 1. Goals and Non-Goals

### Goals
- Drop-in replacement for AWS S3 for clients that speak the S3 REST API (AWS SDKs, `aws-cli`, `s3cmd`, `rclone`, MinIO client `mc`).
- Single-node operation against a local POSIX filesystem (ZFS or NFS assumed for durability/snapshots).
- Caller-chosen object IDs (clients can supply the on-disk identifier, not just the S3 key).
- Plain YAML configuration — no embedded database, no external dependencies.
- All AWS S3 endpoints implemented (subject to the phased roadmap in §10).
- Strict POSIX hygiene: service-owned files, mode `0600`/`0700`, no world-readable paths.

### Non-Goals (v1)
- Distributed/clustered mode, erasure coding, or cross-node replication.
- IAM policy language parity (we'll use simple API keys + per-bucket ACLs initially).
- Lambda/event-driven extensions, S3 Object Lambda, or analytics tiers.
- KMS integration (server-side encryption deferred to v2; see §10).

### Decisions (locked in)
- **License**: Apache License 2.0. `LICENSE` file at repo root, SPDX headers (`SPDX-License-Identifier: Apache-2.0`) in every source file, `NOTICE` file maintained for third-party attributions.
- **Runtime**: JVM only. No GraalVM native image. The JVM gives us identical behavior across MidnightBSD, FreeBSD, and Linux, sidesteps the reflection/proxy headaches that Micronaut + native image bring, and keeps the build matrix small. We ship a fat JAR plus a wrapper script; OS packages provide the JRE dependency.
- **Bucket policy language**: a documented subset of the AWS IAM/S3 policy JSON (see §9.1). Full IAM is out of scope.
- **Region model**: single configured region in v1, but the wire format and config schema reserve a region map for a later milestone (M11, see §10) so we don't have to break compatibility later.
- **Pre-signed URL TTL ceiling**: 3 days (259200 seconds). Requests for longer TTLs are rejected at signing time with `InvalidArgument`. Configurable via `auth.presigned_url_max_ttl_seconds` for operators who need a different ceiling, but the daemon hard-caps it at 7 days to match AWS's own upper bound.
- **Signature versions**: both SigV4 and SigV2 supported. SigV4 is the default and the well-tested path; SigV2 is implemented for compatibility with old clients (legacy `s3cmd`, older boto, in-house tools, embedded devices). Disable-able per-deployment via `auth.allow_sigv2: false` for operators who want SigV4-only. When SigV2 is used, an `audit.log` entry records the access key and User-Agent so operators can identify and migrate stragglers.

---

## 2. Naming and Conventions

| Item | Value |
| --- | --- |
| Project name | `leonardo` |
| Daemon binary | `leonardo` |
| Default base directory | `/var/leonardo` |
| Default config file | `/etc/leonardo/leonardo.yaml` |
| Default service user/group | `leonardo:leonardo` |
| Default listen port | `9000` (HTTP), `9001` (admin) |
| Java | OpenJDK 21 (LTS) |
| Framework | Micronaut 4.x |
| HTTP/network | Netty (via Micronaut's Netty server) |
| Build | Gradle (Kotlin DSL) |
| Packaging | fat JAR + MidnightBSD mport + FreeBSD port + Linux tarball/RPM/DEB |
| License | Apache License 2.0 |

---

## 3. On-Disk Layout

Everything lives under the configured `base_dir` (default `/var/leonardo`). All files and directories are owned by `leonardo:leonardo`; directories `0700`, files `0600`.

```
/var/leonardo/
├── config/
│   ├── server.yaml              # runtime overrides (optional)
│   └── api-keys.yaml            # API key → identity mapping
├── buckets/
│   └── <bucket-name>/
│       ├── _meta/
│       │   ├── bucket.yaml      # bucket metadata (versioning, ACL, tags, lifecycle, etc.)
│       │   ├── objects/
│       │   │   └── <key-hash>.yaml   # per-object metadata (one file per object)
│       │   └── uploads/
│       │       └── <upload-id>.yaml  # multipart upload state
│       └── data/
│           └── <object-id>            # the actual object payload
│                                      # object-id is caller-supplied OR generated
├── tmp/
│   └── multipart/
│       └── <upload-id>/
│           ├── part-00001
│           ├── part-00002
│           └── ...
└── logs/
    ├── access.log
    └── audit.log
```

### Key layout notes
- **Object key → on-disk path**: keys can contain `/`, which S3 treats as a logical prefix. We do **not** mirror S3 keys into directory trees on disk; instead, each object's payload is stored flat under `buckets/<bucket>/data/<object-id>`, and the key → object-id mapping lives in the per-object YAML metadata file.
- **Per-object metadata filename**: `<key-hash>.yaml` where `key-hash` is SHA-256 of the full key, hex-encoded. This avoids filesystem-name-length and special-character problems for keys.
- **Caller-chosen object IDs**: see §6.
- **Listing**: implemented by scanning `_meta/objects/` and reading the small YAML headers. For v1 this is acceptable; an in-memory index (rebuilt on startup) accelerates `ListObjectsV2`.

---

## 4. Configuration

A single YAML file drives the daemon. Example:

```yaml
# /etc/leonardo/leonardo.yaml
server:
  host: 0.0.0.0
  port: 9000
  admin_port: 9001
  region: us-east-1
  domain: s3.example.org        # used for virtual-host-style addressing

storage:
  base_dir: /var/leonardo
  tmp_dir: /var/leonardo/tmp
  fsync_on_write: true          # call fsync before reporting PUT success
  max_object_size: 5497558138880   # 5 TiB, matches S3

limits:
  max_multipart_parts: 10000
  min_part_size: 5242880        # 5 MiB
  max_part_size: 5368709120     # 5 GiB
  request_timeout_seconds: 900

auth:
  source: yaml                  # future: 'ldap', 'oidc', 'pam'
  api_keys_file: /var/leonardo/config/api-keys.yaml
  allow_anonymous_read: false
  allow_sigv2: true             # set false for SigV4-only deployments
  presigned_url_max_ttl_seconds: 259200   # 3 days; hard cap is 7 days

logging:
  access_log: /var/leonardo/logs/access.log
  audit_log: /var/leonardo/logs/audit.log
  level: INFO

features:
  versioning_enabled: true      # whether buckets MAY enable versioning
  multipart_enabled: true
  presigned_urls_enabled: true
```

### API keys file

```yaml
# /var/leonardo/config/api-keys.yaml
# NOTE: plaintext for v1; v2 will move to scrypt/argon2-hashed secrets
# or external secret manager. Do NOT consider this format stable.
keys:
  - access_key: AKIAEXAMPLE1234
    secret_key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
    identity: alice
    enabled: true
  - access_key: AKIAEXAMPLE5678
    secret_key: ...
    identity: backup-bot
    enabled: true
```

### Bucket metadata file

```yaml
# /var/leonardo/buckets/photos/_meta/bucket.yaml
name: photos
created_at: 2026-05-25T14:30:00Z
owner: alice
region: us-east-1

versioning:
  status: Enabled          # Enabled | Suspended | Disabled
  mfa_delete: false

acl:
  canned: private          # private | public-read | public-read-write | authenticated-read
  grants:
    - grantee: alice
      permission: FULL_CONTROL
    - grantee: backup-bot
      permission: READ

tags:
  env: prod
  team: media

lifecycle:
  rules:
    - id: expire-old
      status: Enabled
      prefix: tmp/
      expiration_days: 7

cors:
  rules: []

website:
  enabled: false

encryption:
  default: none            # none | AES256 (v2)

object_lock:
  enabled: false           # set at creation time only, per S3 rules

policy: null               # raw S3 bucket-policy JSON (v2)

# Leonardo-specific options
caller_object_ids: allowed   # required | allowed | forbidden
fsync_on_write: inherit
path_quota_bytes: null
immutable: false
default_content_type: null   # e.g. "application/vnd.midnightbsd.mport"
rate_limit:
  requests_per_second: null  # null = unlimited
  bytes_per_second: null
  burst_multiplier: 2
  scope: per_identity
```

### Per-object metadata file

```yaml
# /var/leonardo/buckets/photos/_meta/objects/<sha256-of-key>.yaml
key: holidays/2025/beach.jpg
object_id: 01HXYZ...ULID      # caller-chosen or server-generated; the data filename
size: 2847193
content_type: image/jpeg
etag: d41d8cd98f00b204e9800998ecf8427e
created_at: 2026-05-25T14:35:12Z
last_modified: 2026-05-25T14:35:12Z
storage_class: STANDARD
versions:                  # present when bucket versioning is Enabled
  - version_id: "null"     # or a server-generated ULID
    object_id: 01HXYZ...ULID
    size: 2847193
    etag: d41d8cd9...
    delete_marker: false
    created_at: 2026-05-25T14:35:12Z
user_metadata:
  x-amz-meta-camera: leica-q
  x-amz-meta-location: kefalonia
tags:
  album: holidays-2025
acl:
  canned: private
legal_hold: false
retention: null
```

---

## 5. Architecture

### Module/package layout (Gradle multi-module)

```
leonardo/
├── leonardo-app/           # Micronaut application, main(), Netty wiring
├── leonardo-api/           # S3 REST controllers, request/response binding
├── leonardo-core/          # domain model, services, business logic
├── leonardo-storage/       # filesystem backend, locking, fsync, layout
├── leonardo-auth/          # SigV2 + SigV4 verification, key store
├── leonardo-yaml/          # YAML metadata reader/writer with atomic rename
├── leonardo-xml/           # S3 XML marshalling (Jackson XML / JAXB-free)
├── leonardo-admin/         # admin endpoints (health, metrics, key reload)
└── leonardo-cli/           # leonardo-admin CLI for ops tasks
```

### Request lifecycle
1. Netty accepts the request → Micronaut routes it.
2. **Auth filter**: parse `Authorization` header, identify SigV2 vs SigV4, recompute signature against `api-keys.yaml`-resolved secret, reject if mismatched. Pre-signed URLs validated on the same path.
3. **Bucket router**: detect virtual-host vs path style (`bucket.s3.example.org/key` vs `s3.example.org/bucket/key`).
4. **Authorization**: check bucket ACL/policy for the identified caller and operation.
5. **Controller**: delegates to a service in `leonardo-core`.
6. **Storage layer**: serializes/deserializes YAML metadata, streams payload bytes to/from `data/<object-id>` using Netty zero-copy where possible (`FileRegion`/`sendfile`).
7. **Response**: XML body where S3 requires it, correct headers (`ETag`, `x-amz-request-id`, `x-amz-version-id`, etc.).

### Concurrency model
- **Per-object lock**: striped `ReentrantReadWriteLock` map keyed by `<bucket>/<key>`. Reads share, writes exclusive. Lock acquisition is short-lived — held across metadata read/write only, never across payload streaming.
- **Per-bucket metadata lock**: separate stripe for `bucket.yaml` mutations.
- **Atomic writes**: every YAML write goes to `<file>.tmp`, then `fsync(tmp)`, then `rename(tmp, file)`, then `fsync(dir)`. This is the only way to make YAML metadata crash-safe on a POSIX filesystem.
- **Payload writes**: stream to `tmp/multipart/<upload-id>/...` or `tmp/<random>`, then `fsync` + `rename` into `data/<object-id>`. ZFS makes this cheap; NFS users get correct semantics but slower fsync.

### Threading
- Netty event loops handle I/O.
- A separate bounded `VirtualThreadPerTaskExecutor` (Java 21 virtual threads) handles blocking filesystem work, keeping event loops responsive.

---

## 6. Caller-Chosen Object IDs

This is one of the headline features distinguishing Leonardo from MinIO.

### Wire-level mechanism
Clients supply the desired on-disk filename via a custom request header on `PutObject` and `CreateMultipartUpload`:

```
x-leonardo-object-id: 01HXYZABCDEF1234567890
```

### Rules
- If header is **absent**, the server generates a ULID and uses it as the object-id.
- If header is **present**, the value must match `^[A-Za-z0-9._-]{1,128}$`. Reject `..`, leading `.`, slashes, NULs, and anything that would escape `data/`.
- The object-id must be **unique within the bucket's `data/` directory**. If a collision occurs, the PUT fails with HTTP 409 `ObjectIdConflict` (a Leonardo-specific error code that AWS SDKs surface verbatim).
- When versioning is **Enabled**, each new version gets its own object-id; if the caller supplies one for a new version, it must still be unique within the bucket.
- On `DeleteObject` of the current version, the data file is removed only when no version references it.

### Why this is useful
- Lets MidnightBSD / mports stage build artifacts with deterministic, content-addressable filenames (e.g., the package's `pkg-name-version.tgz` hash) that match what's already on disk.
- Lets backup tools place data into Leonardo without re-encoding identifiers.
- Lets ZFS snapshot-diff tooling reason about object payloads directly.

---

## 7. Security and POSIX Hygiene

- The daemon refuses to start if `base_dir` is not owned by the configured service user, or if any file under `config/` is group/world-readable.
- A startup self-check verifies umask is `0077`.
- All temp files are created with `O_CREAT | O_EXCL` and mode `0600`.
- Directories created with mode `0700`.
- Optional `chroot(2)` into `base_dir` after binding the listen socket, if the daemon is started as root and configured with `drop_privileges: true`.
- Capsicum support on MidnightBSD (capability mode) for the worker stage after socket bind — a meaningful sandbox win on BSD platforms and a differentiator vs. MinIO.
- API keys file must be mode `0600`, owned by service user; daemon refuses to load it otherwise.
- TLS terminated by Netty when configured; HTTP-only listen is allowed but logs a warning.

---

## 8. S3 Compatibility Surface

We target the full operation set from the AWS S3 API reference. Operations are grouped below by implementation priority. Each phase ships a working subset; nothing in a later phase blocks earlier phases.

### Phase 1 — Core object & bucket I/O (MVP)
`CreateBucket`, `DeleteBucket`, `HeadBucket`, `ListBuckets`, `GetBucketLocation`, `PutObject`, `GetObject`, `HeadObject`, `DeleteObject`, `DeleteObjects`, `CopyObject`, `ListObjects`, `ListObjectsV2`.

### Phase 2 — Multipart & integrity
`CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`, `CompleteMultipartUpload`, `AbortMultipartUpload`, `ListMultipartUploads`, `ListParts`, `GetObjectAttributes`.

### Phase 3 — Bucket configuration
`PutBucketAcl`, `GetBucketAcl`, `PutBucketCors`, `GetBucketCors`, `DeleteBucketCors`, `PutBucketTagging`, `GetBucketTagging`, `DeleteBucketTagging`, `PutBucketVersioning`, `GetBucketVersioning`, `PutBucketWebsite`, `GetBucketWebsite`, `DeleteBucketWebsite`, `PutBucketLifecycleConfiguration`, `GetBucketLifecycleConfiguration`, `DeleteBucketLifecycle`, `PutBucketPolicy`, `GetBucketPolicy`, `DeleteBucketPolicy`, `GetBucketPolicyStatus`, `PutPublicAccessBlock`, `GetPublicAccessBlock`, `DeletePublicAccessBlock`, `PutBucketRequestPayment`, `GetBucketRequestPayment`.

### Phase 4 — Object configuration
`PutObjectAcl`, `GetObjectAcl`, `PutObjectTagging`, `GetObjectTagging`, `DeleteObjectTagging`, `ListObjectVersions`, `RestoreObject`, `RenameObject`.

### Phase 5 — Versioning, locking, retention
`PutObjectLegalHold`, `GetObjectLegalHold`, `PutObjectLockConfiguration`, `GetObjectLockConfiguration`, `PutObjectRetention`, `GetObjectRetention`.

### Phase 6 — Notifications, logging, replication
`PutBucketNotification`, `GetBucketNotification`, `PutBucketNotificationConfiguration`, `GetBucketNotificationConfiguration`, `PutBucketLogging`, `GetBucketLogging`, `PutBucketReplication`, `GetBucketReplication`, `DeleteBucketReplication`.

### Phase 7 — Encryption, ownership, accelerate
`PutBucketEncryption`, `GetBucketEncryption`, `DeleteBucketEncryption`, `UpdateObjectEncryption`, `PutBucketOwnershipControls`, `GetBucketOwnershipControls`, `DeleteBucketOwnershipControls`, `PutBucketAccelerateConfiguration`, `GetBucketAccelerateConfiguration` (no-op accelerate, but return correct shape).

### Phase 8 — Analytics, metrics, inventory, intelligent-tiering, ABAC
`Put/Get/Delete/List` for `BucketAnalyticsConfiguration`, `BucketMetricsConfiguration`, `BucketInventoryConfiguration`, `BucketIntelligentTieringConfiguration`, `GetBucketAbac`, `PutBucketAbac`.

### Phase 9 — Metadata tables, journal, sessions, advanced
`CreateBucketMetadataConfiguration`, `CreateBucketMetadataTableConfiguration`, `DeleteBucketMetadataConfiguration`, `DeleteBucketMetadataTableConfiguration`, `GetBucketMetadataConfiguration`, `GetBucketMetadataTableConfiguration`, `UpdateBucketMetadataInventoryTableConfiguration`, `UpdateBucketMetadataJournalTableConfiguration`, `CreateSession`, `ListDirectoryBuckets`.

### Phase 10 — Streaming, torrents, function-style
`SelectObjectContent` (SQL-over-CSV/JSON/Parquet — implemented via Apache Calcite or a minimal in-house parser), `GetObjectTorrent`, `WriteGetObjectResponse`.

### Phase 11 — Legacy/deprecated for completeness
`GetBucketLifecycle`, `PutBucketLifecycle`, `GetBucketNotification` (legacy), `PutBucketNotification` (legacy), `PutBucketAnalyticsConfiguration`, `PutBucketInventoryConfiguration`, `PutBucketMetricsConfiguration`, `PutBucketIntelligentTieringConfiguration`, deletes for same.

---

## 9. Bucket Options (implemented features)

> Your message cut off at "bucket options:" — the list below is what I've inferred from the S3 reference and our feature goals. **Please review and tell me which to keep, drop, or add.**

Each bucket may set the following, recorded in `bucket.yaml`:

| Option | Backed by | Notes |
| --- | --- | --- |
| Versioning (`Enabled` / `Suspended`) | per-object version list in metadata | requires Phase 4 |
| ACL (canned + explicit grants) | `acl:` block | Phase 3 |
| CORS rules | `cors:` block | Phase 3 |
| Tagging | `tags:` block | Phase 3 |
| Lifecycle (expiration, transitions) | `lifecycle:` block | background sweeper runs hourly |
| Website hosting config | `website:` block | static file serving on a separate hostname |
| Bucket policy (S3 JSON) | `policy:` block | Phase 6 (subset of IAM language) |
| Public access block | flags on bucket | Phase 3 |
| Request payment | `request_payment:` block | no-op semantics (we don't bill) but stored & echoed |
| Encryption at rest (default SSE-S3) | `encryption:` block | Phase 7 — uses libsodium secretstream over the payload |
| Object Lock | `object_lock:` block | set at creation time only |
| Notifications | `notifications:` block | Phase 6 — fan out to webhook URLs / NATS |
| Logging (server access logs to another bucket) | `logging:` block | Phase 6 |
| Replication | `replication:` block | Phase 6 — async copy to remote S3 endpoint |
| Inventory, Analytics, Metrics, Intelligent-Tiering | corresponding blocks | Phase 8 |
| ABAC | `abac:` block | Phase 8 |

### Leonardo-specific bucket options
- `caller_object_ids: required | allowed | forbidden` — controls whether `x-leonardo-object-id` is honored on writes to this bucket.
- `fsync_on_write: inherit | true | false` — per-bucket override of the global setting.
- `path_quota_bytes: <N>` — soft quota; PUT returns `QuotaExceeded` when crossed.
- `rate_limit:` — per-bucket throttling. Token-bucket algorithm, evaluated per identity (or anonymously when unauthenticated). Returns HTTP 503 with `SlowDown` error code when exceeded, matching S3's throttling response.
  ```yaml
  rate_limit:
    requests_per_second: 500       # null = unlimited
    bytes_per_second: 104857600    # 100 MiB/s; null = unlimited
    burst_multiplier: 2            # bucket capacity = rate × multiplier
    scope: per_identity            # per_identity | per_bucket
  ```
- `immutable: true | false` — when `true`, the bucket rejects all `DeleteObject`, `DeleteObjects`, `PutObject` (on existing keys), `CopyObject` (to existing keys), and `RenameObject` requests with HTTP 403 `BucketImmutable`. New objects with new keys are still allowed. Simpler than Object Lock — no per-object retention periods, no legal holds, no governance/compliance modes. The flag itself can only be cleared by an operator via the `leonardo-admin` CLI, never via the S3 API. Intended for archival, audit-log, and write-once-read-many workloads. Mutually exclusive with `object_lock.enabled: true` (use one or the other, not both); when `immutable: true` is set, `versioning` is forced to `Disabled` since version history is meaningless without overwrites or deletes.
- `default_content_type: <mime-type>` — when set, PUT requests that arrive without a `Content-Type` header (or with `application/octet-stream`, which is the SDK default when no type is detected) get this value substituted before the object metadata is written. Useful for buckets that serve a single media type, e.g. `image/jpeg` for a photo bucket or `application/vnd.midnightbsd.mport` for a package repository.

### 9.1 Bucket Policy Subset

Full AWS IAM is a large language with dozens of condition operators, variables, and policy variables. Leonardo implements a documented subset that covers the cases that real-world S3 clients actually use, and rejects (with a clear `MalformedPolicy` error) anything outside it. The subset is intentionally additive — we can broaden it in later versions without breaking existing policies.

**Supported top-level shape:**
```json
{
  "Version": "2012-10-17",
  "Id": "optional-policy-id",
  "Statement": [ { ... }, ... ]
}
```
`Version` must be exactly `"2012-10-17"`. Any other value rejected.

**Statement fields:**

| Field | Supported? | Notes |
| --- | --- | --- |
| `Sid` | yes | string, optional, informational only |
| `Effect` | yes | `Allow` or `Deny` only |
| `Principal` | yes | `"*"` (anonymous), `{"AWS": "<identity>"}`, or `{"AWS": ["<id1>", "<id2>"]}`. Identity strings are matched against `identity:` values from `api-keys.yaml`. Full ARN syntax is **not** parsed — we accept bare identity names. |
| `NotPrincipal` | no | rejected in v1 (rare and easy to misuse) |
| `Action` | yes | `"s3:*"`, `"s3:GetObject"`, etc. Wildcards `*` and `?` supported. Single string or array. |
| `NotAction` | yes | same shape as `Action` |
| `Resource` | yes | `arn:aws:s3:::<bucket>` and `arn:aws:s3:::<bucket>/<key-pattern>` with `*` and `?` wildcards. Single string or array. |
| `NotResource` | yes | same shape as `Resource` |
| `Condition` | yes (subset) | see below |

**Supported condition operators:**

| Operator | Notes |
| --- | --- |
| `StringEquals`, `StringNotEquals` | exact string match |
| `StringLike`, `StringNotLike` | `*` and `?` wildcards |
| `NumericEquals`, `NumericLessThan`, `NumericGreaterThan`, `NumericLessThanEquals`, `NumericGreaterThanEquals` | for numeric headers |
| `Bool` | true/false |
| `IpAddress`, `NotIpAddress` | CIDR notation, IPv4 and IPv6 |
| `DateEquals`, `DateLessThan`, `DateGreaterThan` | ISO 8601 |

**Not supported (rejected with `MalformedPolicy`):**
- `ForAllValues:*` and `ForAnyValue:*` qualifiers
- `IfExists` qualifier
- Policy variables (`${aws:username}`, `${s3:prefix}`, etc.) — treated as literal strings if present
- ARN-format principal parsing (we use bare identity names; the `"AWS": "arn:..."` form is accepted but we extract the trailing identity component only)
- `Federated`, `Service`, `CanonicalUser` principal types

**Supported condition keys:**
- `aws:SourceIp` — caller IP, IPv4/IPv6 in CIDR
- `aws:SecureTransport` — true if TLS
- `aws:CurrentTime` — server clock
- `s3:prefix` — for `ListBucket`
- `s3:x-amz-acl` — for canned ACL on PutObject
- `s3:x-amz-server-side-encryption` — for SSE enforcement (v2)
- Arbitrary `s3:ExistingObjectTag/<key>` and request tag conditions (v2 milestone)

**Evaluation algorithm**: standard IAM semantics — explicit `Deny` wins, otherwise `Allow` required, otherwise implicit deny. ACL grants are evaluated **before** policy when policy is absent; when a policy exists on the bucket, policy is authoritative and ACL is informational only (matches AWS behavior with `BucketOwnerEnforced` ownership).

Policy bodies are validated synchronously on `PutBucketPolicy`. Invalid or out-of-subset constructs are rejected with HTTP 400 and a `MalformedPolicy` error code whose message names the offending field/operator.

### 9.2 Region Model

In v1 the server has exactly one region, configured via `server.region` (default `us-east-1`). All buckets report this region from `GetBucketLocation`. The `CreateBucket` request's `LocationConstraint` body is parsed and validated against the configured region; mismatched values are rejected with `InvalidLocationConstraint`, matching AWS behavior.

For forward compatibility, the configuration schema already accepts (but does not yet act on) a region map. The intent is that M11 introduces multi-region routing without breaking existing config files:

```yaml
server:
  region: us-east-1                # primary region, always required
  region_map:                      # v1: parsed and validated, otherwise ignored
    us-east-1:
      endpoint: s3.example.org
    eu-west-1:
      endpoint: s3-eu.example.org
      forward_to: https://leonardo-eu.internal:9000
```

Until M11, any `region_map` entry whose region differs from `server.region` is logged as a warning at startup and otherwise ignored. Once M11 lands, those entries become live routing rules: requests addressed to a non-local region are either proxied to the configured upstream or rejected with `PermanentRedirect`, depending on per-region settings.

---

## 10. Roadmap

| Milestone | Deliverable | Target |
| --- | --- | --- |
| **M0** | Repo, build, CI (GitHub Actions + cirrus for MidnightBSD), skeleton Micronaut app, health endpoint | Week 1–2 |
| **M1** | Auth (SigV2 + SigV4), API key loader, request-signing test harness against AWS SDK v2 | Week 3–4 |
| **M2** | Phase 1 endpoints (bucket + object CRUD), atomic YAML metadata, caller-chosen object IDs, fsync-rename storage layer | Week 5–8 |
| **M3** | Phase 2 (multipart) + integrity (CRC32C, SHA-256 trailers) | Week 9–10 |
| **M4** | Phase 3 (bucket config) | Week 11–13 |
| **M5** | Phase 4 + 5 (object config, versioning, locking, retention) | Week 14–16 |
| **M6** | Conformance pass: `aws-cli`, `s3cmd`, `rclone`, `mc`, `s3fs-fuse`, Terraform AWS provider | Week 17–18 |
| **M7** | Phase 6 (notifications, logging, replication) | Week 19–21 |
| **M8** | Phase 7 (encryption, ownership) | Week 22–24 |
| **M9** | Phase 8–11 (long tail) | Week 25+ |
| **M10** | MidnightBSD mport, FreeBSD port, Linux packages (tarball/RPM/DEB), signed releases | parallel from M6 |
| **M11** | Multi-region routing: activate `region_map`, implement cross-region proxy + `PermanentRedirect`, region-aware SigV4 | post-1.0 |

---

## 11. Testing Strategy

- **Unit tests**: per service, Micronaut `@MicronautTest`, JUnit 5.
- **Storage layer property tests**: jqwik, focused on crash-safety invariants (no partial writes after kill -9 + restart).
- **Conformance suite**: ported subset of the [s3-tests](https://github.com/ceph/s3-tests) Ceph project — the de-facto S3 compatibility battery. Run nightly in CI.
- **SDK matrix**: AWS SDK for Java v2, AWS SDK for Python (boto3), `aws-cli`, `mc`, `s3cmd`, `rclone`. Each runs a fixed scenario against a Leonardo instance.
- **Fault injection**: a `chaos` profile that randomly delays/drops fsyncs, fails renames, and SIGKILLs the JVM mid-PUT — verifies recovery on restart.
- **Performance**: JMH microbenchmarks for the YAML metadata path; `wrk` and `warp` (MinIO's S3 benchmark tool) for throughput on a ZFS-backed test rig.

---

## 12. Open Questions

All initial design questions have been resolved (see the Decisions block in §1 and the confirmed bucket-options list in §9). New questions that surface during implementation will be tracked in the repo's issue tracker rather than here.

---

## 13. Risks

| Risk | Mitigation |
| --- | --- |
| YAML metadata read overhead dominates `ListObjects` at high object counts | Lazy in-memory index built on startup, kept warm; flush to a compact `index.bin` on shutdown |
| SigV4 corner cases (chunked, trailer, streaming) cause subtle SDK incompatibilities | Pin to the conformance suite; gate releases on it |
| SigV2 sub-resource canonicalization differs subtly from SigV4 (alphabetical sub-resource ordering, different signed-header rules, no payload hashing) — easy to get wrong | Implement SigV2 against the original AWS S3 SigV2 reference and verify with archived `s3cmd 1.x` and `boto 2.x` request fixtures; treat any conformance-suite SigV2 failure as a release blocker |
| NFS semantics (no real fsync-on-rename, weak cache coherency) cause data loss | Detect NFS at startup and refuse to run unless `storage.allow_nfs: true` is explicit; document expected NFS export options (`sync`, `no_wdelay`) |
| Caller-chosen object IDs become a security footgun (path traversal) | Strict regex + canonicalize-and-verify-parent check on every write |
| Phase 10 (`SelectObjectContent`) is a large body of work that few clients use | Stub with `NotImplemented` until a real user asks |

---

## 14. References

- AWS S3 API reference: https://docs.aws.amazon.com/AmazonS3/latest/API/
- Early MinIO design (pre-erasure-code era): https://github.com/minio/minio/tree/RELEASE.2017-01-25T03-14-52Z
- Micronaut 4 docs: https://docs.micronaut.io/latest/guide/
- Netty user guide: https://netty.io/wiki/user-guide.html
- Ceph s3-tests conformance: https://github.com/ceph/s3-tests
