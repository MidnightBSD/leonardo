# Leonardo tools

Operator utilities that live outside the server build. Each tool is a single
file with its own dependencies noted at the top.

## minio-to-leonardo.py

Copies buckets and objects from a MinIO server (or any other S3-compatible
server) into Leonardo over the S3 API. Objects stream straight from source to
destination; nothing is spooled to local disk, so the machine running the
script only needs network access to both servers.

### Requirements

- Python 3.9 or newer
- `boto3` (`pip install boto3`, or `python3 -m venv venv && venv/bin/pip install boto3`)
- An API key on the Leonardo side (see `config/api-keys.yaml.sample`) and
  credentials that can read the MinIO buckets

### Quick start

```sh
export MINIO_ENDPOINT=http://minio.example.org:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin

export LEONARDO_ENDPOINT=http://leonardo.example.org:9000
export LEONARDO_ACCESS_KEY=AKIAEXAMPLE1234567890
export LEONARDO_SECRET_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY

# See what would happen
tools/minio-to-leonardo.py --dry-run

# Copy every bucket
tools/minio-to-leonardo.py

# Copy one bucket under a new name, with its version history, 16 copies at a time
tools/minio-to-leonardo.py --bucket photos=photos-archive --versions --workers 16
```

Every flag can be given on the command line instead of through the
environment; run `tools/minio-to-leonardo.py --help` for the full list.
`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` are accepted as fallbacks for the
source credentials.

### What is copied

| Item | Copied | Notes |
| --- | --- | --- |
| Object payload | yes | single PUT up to `--part-size` (default 64 MiB), multipart above it |
| Content-Type | yes | |
| User metadata (`x-amz-meta-*`) | yes | |
| Object tags | yes | disable with `--no-tags` |
| Cache-Control, Content-Encoding, Content-Disposition, Content-Language, Expires | sent | Leonardo stores only Content-Type and user metadata today, so these do not survive the copy |
| Object version history and delete markers | with `--versions` | replayed oldest to newest; the destination bucket has versioning enabled first |
| Bucket versioning, tagging, CORS, lifecycle | yes | choose with `--bucket-config versioning,tagging,cors,lifecycle,policy` or `none` |
| Bucket policy | opt-in | add `policy` to `--bucket-config`; review MinIO policies first, their principals may not map onto Leonardo identities |
| ACLs, object lock, storage class | no | MinIO does not expose per-object ACLs; set these on Leonardo afterwards if needed |

Missing or unsupported configuration on the source (MinIO has no per-bucket
CORS, for example) is skipped with a debug message, and a rejected
configuration on the destination is a warning, not a failure.

### Verification and re-runs

After each copy the script HEADs the destination object and checks that the
size matches and that the ETag equals the MD5 (or the standard S3 composite
multipart MD5) computed while streaming. A mismatch counts as a failure.

Running the script again is safe. Objects already present on the destination
with the same size and ETag are skipped, so an interrupted migration can be
resumed by re-running the same command. Use `--overwrite` to force a fresh
copy. Objects whose source ETag is a composite multipart ETag are skipped on
size alone, because composite ETags depend on the part size the original
uploader chose.

Transient errors (5xx, timeouts, connection resets) are retried per object;
tune with `--retries`. Failures are listed at the end and the exit code is
non-zero; `--failed-list FILE` writes the failed keys to a file for a targeted
re-run.

### Scope control

- `--bucket SRC[=DST]` (repeatable) limits the run to named buckets and can
  rename them on the destination. Without it every bucket on the source is
  copied.
- `--prefix` copies only keys under a prefix.
- `--exclude GLOB` (repeatable) skips keys matching a shell-style pattern,
  for example `--exclude '*.tmp' --exclude 'cache/*'`.

### Operational notes

- Keep the Leonardo region (`--dst-region`, default `us-east-1`) equal to
  `leonardo.server.region` in the server configuration, or every request
  fails signature verification.
- `--part-size` is also the single-PUT threshold. Memory use is roughly
  `--workers` multiplied by `--part-size`. Leonardo accepts parts between
  5 MiB and 5 GiB and at most 10,000 parts; the script grows the part size
  automatically for objects that would otherwise exceed the part limit.
- Both endpoints are addressed path-style with SigV4. Use `--insecure` or
  `--ca-bundle` for TLS endpoints with private certificates.
- Freeze writes on the MinIO side before the final pass, then re-run the
  script once more to pick up anything written during the first pass.
