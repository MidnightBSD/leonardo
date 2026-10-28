#!/usr/bin/env python3
#
# Copyright 2026 The Leonardo Authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# SPDX-License-Identifier: Apache-2.0
#
"""
Migrate buckets and objects from a MinIO server into a Leonardo server.

Both ends are talked to over the S3 API with boto3, so the script also works
for any other S3-compatible source. Objects are streamed straight from the
source into the destination: nothing is spooled to local disk.

What is carried over for every object:
  - payload (single PUT below the part size, multipart above it)
  - Content-Type, Content-Encoding, Content-Disposition, Content-Language,
    Cache-Control, Expires
  - user metadata (x-amz-meta-*)
  - object tags (unless --no-tags), via PutObjectTagging after the copy
Optionally, with --versions, every version and delete marker of a versioned
bucket is replayed oldest-to-newest so the destination history matches.

Per bucket, the following configuration is copied when present on the source
(select with --bucket-config): versioning, tagging, cors, lifecycle, policy.

Every copied object is verified afterwards by HEAD on the destination: size
must match, and the ETag must equal the MD5 (or the S3 composite multipart
MD5) computed while streaming.

Requires Python 3.9+ and boto3 (pip install boto3).
"""

from __future__ import annotations

import argparse
import concurrent.futures
import fnmatch
import hashlib
import logging
import math
import os
import re
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

try:
    import boto3
    import botocore
    from botocore.config import Config
    from botocore.exceptions import ClientError
except ImportError:  # pragma: no cover
    sys.stderr.write("boto3 is required: pip install boto3\n")
    sys.exit(2)

LOG = logging.getLogger("minio-to-leonardo")

MIB = 1024 * 1024
MIN_PART_SIZE = 5 * MIB
MAX_PARTS = 10_000

ALL_BUCKET_CONFIG = ("versioning", "tagging", "cors", "lifecycle", "policy")
DEFAULT_BUCKET_CONFIG = ("versioning", "tagging", "cors", "lifecycle")

# Headers on HEAD/GET that map 1:1 onto PutObject / CreateMultipartUpload args.
PASSTHROUGH_ARGS = (
    "ContentType",
    "ContentEncoding",
    "ContentDisposition",
    "ContentLanguage",
    "CacheControl",
    "Expires",
)


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #

def human(n: float) -> str:
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if n < 1024 or unit == "TiB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024
    return f"{n:.1f} TiB"


def parse_size(text: str) -> int:
    """Parse '64M', '5MiB', '1g', '5242880' etc. into bytes."""
    m = re.fullmatch(r"\s*(\d+)\s*([KMG]?)(?:I?B)?\s*", text, re.IGNORECASE)
    if not m:
        raise argparse.ArgumentTypeError(f"invalid size: {text!r}")
    mult = {"": 1, "K": 1024, "M": MIB, "G": 1024 * MIB}[m.group(2).upper()]
    return int(m.group(1)) * mult


def strip_quotes(etag: Optional[str]) -> str:
    return (etag or "").strip('"')


def error_code(exc: ClientError) -> str:
    return exc.response.get("Error", {}).get("Code", "")


def http_status(exc: ClientError) -> int:
    return int(exc.response.get("ResponseMetadata", {}).get("HTTPStatusCode", 0))


def read_exact(body: Any, n: int) -> bytes:
    """Read up to n bytes from a StreamingBody, looping over short reads."""
    chunks: list[bytes] = []
    remaining = n
    while remaining > 0:
        piece = body.read(remaining)
        if not piece:
            break
        chunks.append(piece)
        remaining -= len(piece)
    return b"".join(chunks)


def choose_part_size(size: int, requested: int) -> int:
    """Largest of the requested part size and the smallest size that fits in MAX_PARTS parts."""
    needed = math.ceil(size / MAX_PARTS)
    needed = max(MIN_PART_SIZE, math.ceil(needed / MIB) * MIB)
    return max(requested, needed)


# --------------------------------------------------------------------------- #
# Endpoints
# --------------------------------------------------------------------------- #

@dataclass
class Endpoint:
    name: str
    url: str
    access_key: str
    secret_key: str
    region: str
    verify: Any = True  # True, False, or a CA-bundle path

    def client(self, pool: int):
        cfg_kwargs: dict[str, Any] = dict(
            signature_version="s3v4",
            s3={"addressing_style": "path"},
            retries={"max_attempts": 5, "mode": "standard"},
            max_pool_connections=pool,
            connect_timeout=30,
            read_timeout=900,
        )
        # botocore >= 1.36 sends CRC checksums / aws-chunked trailers by default.
        # Neither MinIO nor Leonardo need that, and some builds reject it, so
        # only compute checksums when an operation really requires one.
        try:
            Config(request_checksum_calculation="when_required")
            cfg_kwargs["request_checksum_calculation"] = "when_required"
            cfg_kwargs["response_checksum_validation"] = "when_required"
        except TypeError:
            pass
        return boto3.client(
            "s3",
            endpoint_url=self.url,
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
            verify=self.verify,
            config=Config(**cfg_kwargs),
        )


# --------------------------------------------------------------------------- #
# Migration
# --------------------------------------------------------------------------- #

@dataclass
class Stats:
    copied: int = 0
    skipped: int = 0
    failed: int = 0
    deleted: int = 0        # delete markers replayed
    bytes: int = 0
    failures: list[str] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def ok(self, nbytes: int) -> None:
        with self.lock:
            self.copied += 1
            self.bytes += nbytes

    def skip(self) -> None:
        with self.lock:
            self.skipped += 1

    def fail(self, what: str) -> None:
        with self.lock:
            self.failed += 1
            self.failures.append(what)

    def marker(self) -> None:
        with self.lock:
            self.deleted += 1


class Migrator:
    def __init__(self, args: argparse.Namespace, src: Endpoint, dst: Endpoint) -> None:
        self.args = args
        self.src = src.client(pool=args.workers + 4)
        self.dst = dst.client(pool=args.workers + 4)
        self.stats = Stats()
        self.excludes: list[str] = args.exclude or []
        self.part_size = parse_size(args.part_size)
        if self.part_size < MIN_PART_SIZE:
            raise SystemExit(f"--part-size must be at least 5 MiB (got {self.part_size} bytes)")

    # -- bucket level ------------------------------------------------------- #

    def source_buckets(self) -> list[tuple[str, str]]:
        """Return (source_bucket, destination_bucket) pairs."""
        if self.args.bucket:
            pairs = []
            for spec in self.args.bucket:
                s, _, d = spec.partition("=")
                pairs.append((s, d or s))
            return pairs
        names = [b["Name"] for b in self.src.list_buckets().get("Buckets", [])]
        return [(n, n) for n in sorted(names)]

    def ensure_destination_bucket(self, bucket: str) -> None:
        try:
            self.dst.head_bucket(Bucket=bucket)
            LOG.info("[%s] destination bucket exists", bucket)
            return
        except ClientError as exc:
            if http_status(exc) != 404:
                raise
        if self.args.dry_run:
            LOG.info("[%s] would create destination bucket", bucket)
            return
        kwargs: dict[str, Any] = {"Bucket": bucket}
        if self.args.dst_region and self.args.dst_region != "us-east-1":
            kwargs["CreateBucketConfiguration"] = {"LocationConstraint": self.args.dst_region}
        try:
            self.dst.create_bucket(**kwargs)
            LOG.info("[%s] created destination bucket", bucket)
        except ClientError as exc:
            if error_code(exc) not in ("BucketAlreadyOwnedByYou", "BucketAlreadyExists"):
                raise

    def source_versioning_enabled(self, bucket: str) -> bool:
        try:
            return self.src.get_bucket_versioning(Bucket=bucket).get("Status") == "Enabled"
        except ClientError as exc:
            LOG.debug("[%s] get_bucket_versioning on source: %s", bucket, exc)
            return False

    def copy_bucket_config(self, sbucket: str, dbucket: str, wanted: set[str]) -> None:
        def apply(what: str, fetch, put, missing_codes: tuple[str, ...]) -> None:
            if what not in wanted:
                return
            try:
                conf = fetch()
            except ClientError as exc:
                if error_code(exc) in missing_codes or http_status(exc) == 404:
                    LOG.debug("[%s] no %s configuration on source", sbucket, what)
                    return
                LOG.warning("[%s] could not read %s configuration from source: %s", sbucket, what, exc)
                return
            if conf is None:
                return
            if self.args.dry_run:
                LOG.info("[%s] would copy %s configuration", dbucket, what)
                return
            try:
                put(conf)
                LOG.info("[%s] copied %s configuration", dbucket, what)
            except ClientError as exc:
                LOG.warning("[%s] destination rejected %s configuration: %s", dbucket, what, exc)

        def get_versioning():
            status = self.src.get_bucket_versioning(Bucket=sbucket).get("Status")
            return {"Status": status} if status in ("Enabled", "Suspended") else None

        apply(
            "versioning",
            get_versioning,
            lambda c: self.dst.put_bucket_versioning(Bucket=dbucket, VersioningConfiguration=c),
            (),
        )
        apply(
            "tagging",
            lambda: {"TagSet": self.src.get_bucket_tagging(Bucket=sbucket)["TagSet"]},
            lambda c: self.dst.put_bucket_tagging(Bucket=dbucket, Tagging=c),
            ("NoSuchTagSet", "NoSuchTagSetError"),
        )
        apply(
            "cors",
            lambda: {"CORSRules": self.src.get_bucket_cors(Bucket=sbucket)["CORSRules"]},
            lambda c: self.dst.put_bucket_cors(Bucket=dbucket, CORSConfiguration=c),
            ("NoSuchCORSConfiguration",),
        )
        apply(
            "lifecycle",
            lambda: {"Rules": self.src.get_bucket_lifecycle_configuration(Bucket=sbucket)["Rules"]},
            lambda c: self.dst.put_bucket_lifecycle_configuration(Bucket=dbucket, LifecycleConfiguration=c),
            ("NoSuchLifecycleConfiguration",),
        )
        apply(
            "policy",
            lambda: self.src.get_bucket_policy(Bucket=sbucket)["Policy"],
            lambda c: self.dst.put_bucket_policy(Bucket=dbucket, Policy=c),
            ("NoSuchBucketPolicy",),
        )

    # -- listing ------------------------------------------------------------ #

    def excluded(self, key: str) -> bool:
        return any(fnmatch.fnmatchcase(key, pat) for pat in self.excludes)

    def list_current(self, bucket: str) -> Iterable[dict]:
        paginator = self.src.get_paginator("list_objects_v2")
        kwargs = {"Bucket": bucket}
        if self.args.prefix:
            kwargs["Prefix"] = self.args.prefix
        for page in paginator.paginate(**kwargs):
            for obj in page.get("Contents", []):
                if not self.excluded(obj["Key"]):
                    yield {"Key": obj["Key"], "Size": obj.get("Size", 0), "ETag": obj.get("ETag")}

    def list_versions(self, bucket: str) -> Iterable[tuple[str, list[dict]]]:
        """Yield (key, [versions oldest-first]) including delete markers."""
        paginator = self.src.get_paginator("list_object_versions")
        kwargs = {"Bucket": bucket}
        if self.args.prefix:
            kwargs["Prefix"] = self.args.prefix
        pending: dict[str, list[dict]] = {}
        order: list[str] = []
        for page in paginator.paginate(**kwargs):
            for v in page.get("Versions", []):
                v = dict(v, IsDeleteMarker=False)
                if v["Key"] not in pending:
                    order.append(v["Key"])
                pending.setdefault(v["Key"], []).append(v)
            for m in page.get("DeleteMarkers", []):
                m = dict(m, IsDeleteMarker=True)
                if m["Key"] not in pending:
                    order.append(m["Key"])
                pending.setdefault(m["Key"], []).append(m)
            # Keys are returned in order; everything but the last key on this
            # page is complete, so it can be handed out now.
            while len(order) > 1:
                k = order.pop(0)
                yield k, self._sorted_versions(pending.pop(k))
        for k in order:
            yield k, self._sorted_versions(pending.pop(k))

    @staticmethod
    def _sorted_versions(versions: list[dict]) -> list[dict]:
        # Oldest first; LastModified ties are broken by the listing order
        # (newest first), reversed.
        indexed = list(enumerate(versions))
        indexed.sort(key=lambda iv: (iv[1]["LastModified"], -iv[0]))
        return [v for _, v in indexed]

    # -- object level ------------------------------------------------------- #

    def destination_matches(self, dbucket: str, key: str, size: int, src_etag: str) -> bool:
        """True if the destination already holds an object that looks identical."""
        try:
            head = self.dst.head_object(Bucket=dbucket, Key=key)
        except ClientError as exc:
            if http_status(exc) in (403, 404):
                return False
            raise
        if head.get("ContentLength") != size:
            return False
        dst_etag = strip_quotes(head.get("ETag"))
        # Composite (multipart) ETags depend on part size, so two different
        # uploads of the same bytes can legitimately differ. Only compare when
        # both are plain MD5s.
        if "-" in src_etag or "-" in dst_etag:
            return True
        return src_etag == dst_etag

    def copy_object(self, sbucket: str, dbucket: str, key: str, version_id: Optional[str] = None) -> int:
        """Stream one object (or object version) across. Returns bytes copied."""
        get_kwargs: dict[str, Any] = {"Bucket": sbucket, "Key": key}
        if version_id and version_id != "null":
            get_kwargs["VersionId"] = version_id
        head = self.src.head_object(**get_kwargs)
        size = int(head["ContentLength"])

        put_args: dict[str, Any] = {}
        for name in PASSTHROUGH_ARGS:
            if head.get(name) is not None:
                put_args[name] = head[name]
        if head.get("Metadata"):
            put_args["Metadata"] = head["Metadata"]
        tags = [] if self.args.no_tags else self.fetch_tags(get_kwargs)

        resp = self.src.get_object(**get_kwargs)
        body = resp["Body"]
        try:
            if size <= self.part_size:
                etag, new_version = self._put_single(dbucket, key, body, size, put_args)
            else:
                etag, new_version = self._put_multipart(dbucket, key, body, size, put_args)
        finally:
            body.close()

        if not self.args.no_verify:
            self._verify(dbucket, key, size, etag, new_version)
        if tags:
            # Sent as a separate PutObjectTagging call rather than an
            # x-amz-tagging header: that works on every S3 server, including
            # ones that ignore the header on PutObject.
            tag_kwargs: dict[str, Any] = {"Bucket": dbucket, "Key": key, "Tagging": {"TagSet": tags}}
            if new_version:
                tag_kwargs["VersionId"] = new_version
            self.dst.put_object_tagging(**tag_kwargs)
        return size

    def fetch_tags(self, get_kwargs: dict[str, Any]) -> list[dict]:
        try:
            return self.src.get_object_tagging(**get_kwargs).get("TagSet", [])
        except ClientError as exc:
            LOG.debug("get_object_tagging %s: %s", get_kwargs.get("Key"), exc)
            return []

    def _put_single(self, bucket: str, key: str, body: Any, size: int,
                    put_args: dict) -> tuple[str, Optional[str]]:
        """Single PUT. Returns (expected ETag, new version id or None)."""
        data = read_exact(body, size) if size > 0 else b""
        if len(data) != size:
            raise RuntimeError(f"short read from source: expected {size} bytes, got {len(data)}")
        md5 = hashlib.md5(data).hexdigest()  # noqa: S324 - S3 ETags are MD5 by definition
        resp = self.dst.put_object(Bucket=bucket, Key=key, Body=data, ContentLength=size, **put_args)
        return md5, resp.get("VersionId")

    def _put_multipart(self, bucket: str, key: str, body: Any, size: int,
                       put_args: dict) -> tuple[str, Optional[str]]:
        """Multipart upload. Returns (expected composite ETag, new version id or None)."""
        part_size = choose_part_size(size, self.part_size)
        upload = self.dst.create_multipart_upload(Bucket=bucket, Key=key, **put_args)
        upload_id = upload["UploadId"]
        parts: list[dict] = []
        part_md5s: list[bytes] = []
        sent = 0
        try:
            part_number = 1
            while sent < size:
                want = min(part_size, size - sent)
                chunk = read_exact(body, want)
                if len(chunk) != want:
                    raise RuntimeError(
                        f"short read from source at offset {sent}: expected {want} bytes, got {len(chunk)}"
                    )
                digest = hashlib.md5(chunk).digest()  # noqa: S324
                resp = self.dst.upload_part(
                    Bucket=bucket, Key=key, UploadId=upload_id, PartNumber=part_number,
                    Body=chunk, ContentLength=len(chunk),
                )
                parts.append({"PartNumber": part_number, "ETag": resp["ETag"]})
                part_md5s.append(digest)
                sent += len(chunk)
                part_number += 1
                LOG.debug("  %s: part %d/%s uploaded (%s so far)",
                          key, part_number - 1, math.ceil(size / part_size), human(sent))
            done = self.dst.complete_multipart_upload(
                Bucket=bucket, Key=key, UploadId=upload_id, MultipartUpload={"Parts": parts},
            )
        except BaseException:
            try:
                self.dst.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)
            except Exception as abort_exc:  # noqa: BLE001
                LOG.warning("could not abort multipart upload %s for %s: %s", upload_id, key, abort_exc)
            raise
        composite = hashlib.md5(b"".join(part_md5s)).hexdigest()  # noqa: S324
        return f"{composite}-{len(parts)}", done.get("VersionId")

    def _verify(self, bucket: str, key: str, size: int, expected_etag: str,
                version_id: Optional[str] = None) -> None:
        head_kwargs: dict[str, Any] = {"Bucket": bucket, "Key": key}
        if version_id:
            head_kwargs["VersionId"] = version_id
        head = self.dst.head_object(**head_kwargs)
        got_size = int(head.get("ContentLength", -1))
        if got_size != size:
            raise RuntimeError(f"verification failed: destination size {got_size} != source size {size}")
        got_etag = strip_quotes(head.get("ETag"))
        if got_etag != expected_etag:
            raise RuntimeError(f"verification failed: destination ETag {got_etag} != computed {expected_etag}")

    # -- work items --------------------------------------------------------- #

    def migrate_current(self, sbucket: str, dbucket: str, entry: dict) -> None:
        key, size = entry["Key"], int(entry.get("Size") or 0)
        label = f"{sbucket}/{key}"
        try:
            if not self.args.overwrite and self.destination_matches(
                dbucket, key, size, strip_quotes(entry.get("ETag"))
            ):
                LOG.debug("skip  %s (already present)", label)
                self.stats.skip()
                return
            if self.args.dry_run:
                LOG.info("would copy %s (%s)", label, human(size))
                self.stats.ok(size)
                return
            n = self._with_retries(lambda: self.copy_object(sbucket, dbucket, key))
            LOG.info("copied %s (%s)", label, human(n))
            self.stats.ok(n)
        except Exception as exc:  # noqa: BLE001
            LOG.error("FAILED %s: %s", label, exc)
            self.stats.fail(f"{label}: {exc}")

    def destination_has_history(self, dbucket: str, key: str) -> bool:
        """True if any version or delete marker for exactly this key exists on the destination."""
        resp = self.dst.list_object_versions(Bucket=dbucket, Prefix=key, MaxKeys=1)
        for group in ("Versions", "DeleteMarkers"):
            if any(v["Key"] == key for v in resp.get(group, [])):
                return True
        return False

    def migrate_versions(self, sbucket: str, dbucket: str, key: str, versions: list[dict]) -> None:
        label = f"{sbucket}/{key}"
        try:
            if not self.args.overwrite and self.destination_has_history(dbucket, key):
                # Version ids differ between servers, so history cannot be
                # diffed; a key with any history on the destination counts as
                # already migrated. --overwrite replays it on top again.
                LOG.debug("skip  %s (history already present)", label)
                self.stats.skip()
                return
        except ClientError as exc:
            LOG.error("FAILED %s: %s", label, exc)
            self.stats.fail(f"{label}: {exc}")
            return
        for v in versions:
            vid = v.get("VersionId") or "null"
            vlabel = f"{label}@{vid}"
            try:
                if v["IsDeleteMarker"]:
                    if self.args.dry_run:
                        LOG.info("would replay delete marker %s", vlabel)
                    else:
                        self._with_retries(lambda: self.dst.delete_object(Bucket=dbucket, Key=key))
                        LOG.info("delete-marker %s", vlabel)
                    self.stats.marker()
                    continue
                size = int(v.get("Size") or 0)
                if self.args.dry_run:
                    LOG.info("would copy %s (%s)", vlabel, human(size))
                    self.stats.ok(size)
                    continue
                n = self._with_retries(lambda: self.copy_object(sbucket, dbucket, key, vid))
                LOG.info("copied %s (%s)", vlabel, human(n))
                self.stats.ok(n)
            except Exception as exc:  # noqa: BLE001
                LOG.error("FAILED %s: %s", vlabel, exc)
                self.stats.fail(f"{vlabel}: {exc}")
                # Later versions would land out of order; stop this key here.
                return

    def _with_retries(self, fn):
        attempts = max(1, self.args.retries)
        delay = 2.0
        for attempt in range(1, attempts + 1):
            try:
                return fn()
            except Exception as exc:  # noqa: BLE001
                if attempt == attempts or not self._retryable(exc):
                    raise
                LOG.warning("attempt %d/%d failed (%s); retrying in %.0fs", attempt, attempts, exc, delay)
                time.sleep(delay)
                delay = min(delay * 2, 30)
        return None  # unreachable

    @staticmethod
    def _retryable(exc: Exception) -> bool:
        if isinstance(exc, ClientError):
            return http_status(exc) >= 500 or error_code(exc) in ("SlowDown", "RequestTimeout", "Throttling")
        return isinstance(exc, (botocore.exceptions.BotoCoreError, ConnectionError, TimeoutError, RuntimeError))

    # -- driver ------------------------------------------------------------- #

    def run(self) -> Stats:
        wanted = set(self.args.bucket_config)
        pairs = self.source_buckets()
        if not pairs:
            LOG.warning("no buckets found on source")
            return self.stats
        LOG.info("buckets to migrate: %s", ", ".join(f"{s}->{d}" if s != d else s for s, d in pairs))

        with concurrent.futures.ThreadPoolExecutor(max_workers=self.args.workers) as pool:
            for sbucket, dbucket in pairs:
                started = time.monotonic()
                before = (self.stats.copied, self.stats.failed, self.stats.skipped)
                try:
                    self.ensure_destination_bucket(dbucket)
                    # Versioning must be on before objects land, or history is lost.
                    self.copy_bucket_config(sbucket, dbucket, wanted & {"versioning"})
                except ClientError as exc:
                    LOG.error("[%s] cannot prepare destination bucket: %s", dbucket, exc)
                    self.stats.fail(f"{dbucket}: {exc}")
                    continue

                use_versions = self.args.versions
                if use_versions and not self.source_versioning_enabled(sbucket):
                    LOG.info("[%s] source bucket is not versioned; copying current objects only", sbucket)
                    use_versions = False

                futures = []
                if use_versions:
                    for key, versions in self.list_versions(sbucket):
                        if self.excluded(key):
                            continue
                        futures.append(pool.submit(self.migrate_versions, sbucket, dbucket, key, versions))
                        self._drain(futures)
                else:
                    for entry in self.list_current(sbucket):
                        futures.append(pool.submit(self.migrate_current, sbucket, dbucket, entry))
                        self._drain(futures)
                for f in futures:
                    f.result()

                # Remaining config goes last so lifecycle rules cannot expire
                # objects mid-copy and policies cannot block our own writes.
                try:
                    self.copy_bucket_config(sbucket, dbucket, wanted - {"versioning"})
                except ClientError as exc:
                    LOG.warning("[%s] bucket configuration copy failed: %s", dbucket, exc)

                c, f_, s = (self.stats.copied - before[0], self.stats.failed - before[1],
                            self.stats.skipped - before[2])
                LOG.info("[%s] done in %.1fs: %d copied, %d skipped, %d failed",
                         sbucket, time.monotonic() - started, c, s, f_)
        return self.stats

    def _drain(self, futures: list, limit: Optional[int] = None) -> None:
        """Keep the queue of outstanding work bounded so huge listings do not pile up."""
        limit = limit or self.args.workers * 4
        while len(futures) >= limit:
            done = [f for f in futures if f.done()]
            if not done:
                concurrent.futures.wait(futures, return_when=concurrent.futures.FIRST_COMPLETED)
                continue
            for f in done:
                f.result()
                futures.remove(f)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def env(*names: str, default: Optional[str] = None) -> Optional[str]:
    for n in names:
        v = os.environ.get(n)
        if v:
            return v
    return default


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="minio-to-leonardo",
        description="Copy buckets and objects from MinIO (or any S3 server) into Leonardo.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
environment variables (used when the matching flag is absent):
  MINIO_ENDPOINT, MINIO_ACCESS_KEY (or MINIO_ROOT_USER), MINIO_SECRET_KEY (or MINIO_ROOT_PASSWORD), MINIO_REGION
  LEONARDO_ENDPOINT, LEONARDO_ACCESS_KEY, LEONARDO_SECRET_KEY, LEONARDO_REGION

examples:
  # everything, all buckets
  minio-to-leonardo.py --src-endpoint http://minio:9000 --src-access-key minioadmin --src-secret-key minioadmin \\
      --dst-endpoint http://leonardo:9000 --dst-access-key AKIA... --dst-secret-key ...

  # one bucket, renamed, including version history, 16 parallel copies
  minio-to-leonardo.py ... --bucket photos=photos-archive --versions --workers 16

  # see what would happen first
  minio-to-leonardo.py ... --dry-run
""",
    )
    s = p.add_argument_group("source (MinIO)")
    s.add_argument("--src-endpoint", default=env("MINIO_ENDPOINT"), help="e.g. http://minio.example.org:9000")
    s.add_argument("--src-access-key", default=env("MINIO_ACCESS_KEY", "MINIO_ROOT_USER"))
    s.add_argument("--src-secret-key", default=env("MINIO_SECRET_KEY", "MINIO_ROOT_PASSWORD"))
    s.add_argument("--src-region", default=env("MINIO_REGION", default="us-east-1"))

    d = p.add_argument_group("destination (Leonardo)")
    d.add_argument("--dst-endpoint", default=env("LEONARDO_ENDPOINT"), help="e.g. http://leonardo.example.org:9000")
    d.add_argument("--dst-access-key", default=env("LEONARDO_ACCESS_KEY"))
    d.add_argument("--dst-secret-key", default=env("LEONARDO_SECRET_KEY"))
    d.add_argument("--dst-region", default=env("LEONARDO_REGION", default="us-east-1"),
                   help="must match leonardo.server.region on the destination (default: us-east-1)")

    w = p.add_argument_group("what to copy")
    w.add_argument("--bucket", action="append", metavar="SRC[=DST]",
                   help="bucket to copy, optionally renamed on the destination; repeatable. Default: all buckets")
    w.add_argument("--prefix", help="only copy keys starting with this prefix")
    w.add_argument("--exclude", action="append", metavar="GLOB",
                   help="skip keys matching this shell-style pattern; repeatable")
    w.add_argument("--versions", action="store_true",
                   help="replay every object version and delete marker of versioned buckets, oldest first")
    w.add_argument("--no-tags", action="store_true", help="do not copy object tags")
    w.add_argument("--bucket-config", default=",".join(DEFAULT_BUCKET_CONFIG),
                   help="comma-separated bucket settings to copy: %s, or 'none' (default: %%(default)s)"
                        % ",".join(ALL_BUCKET_CONFIG))

    h = p.add_argument_group("how to copy")
    h.add_argument("--workers", type=int, default=8, help="parallel object copies (default: %(default)s)")
    h.add_argument("--part-size", default="64M",
                   help="multipart part size and single-PUT threshold, min 5M (default: %(default)s)")
    h.add_argument("--overwrite", action="store_true",
                   help="copy even when the destination already has an object of the same size and ETag")
    h.add_argument("--no-verify", action="store_true", help="skip the HEAD size/ETag check after each copy")
    h.add_argument("--retries", type=int, default=3, help="attempts per object on transient errors (default: %(default)s)")
    h.add_argument("--dry-run", "-n", action="store_true", help="list what would be done without writing anything")
    h.add_argument("--insecure", action="store_true", help="do not verify TLS certificates")
    h.add_argument("--ca-bundle", help="CA bundle for TLS verification of both endpoints")
    h.add_argument("--failed-list", metavar="FILE", help="write the keys that failed to this file")
    h.add_argument("-v", "--verbose", action="store_true", help="debug logging (per-part progress, skips)")
    h.add_argument("-q", "--quiet", action="store_true", help="only report warnings, failures and the summary")
    return p


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    level = logging.DEBUG if args.verbose else (logging.WARNING if args.quiet else logging.INFO)
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)-7s %(message)s", datefmt="%H:%M:%S")
    for noisy in ("boto3", "botocore", "urllib3", "s3transfer"):
        logging.getLogger(noisy).setLevel(logging.WARNING)

    missing = [n for n in ("src_endpoint", "src_access_key", "src_secret_key",
                           "dst_endpoint", "dst_access_key", "dst_secret_key") if not getattr(args, n)]
    if missing:
        sys.stderr.write("missing: " + ", ".join("--" + m.replace("_", "-") for m in missing) + "\n")
        return 2

    if args.bucket_config.strip().lower() == "none":
        args.bucket_config = []
    else:
        args.bucket_config = [c.strip().lower() for c in args.bucket_config.split(",") if c.strip()]
        bad = [c for c in args.bucket_config if c not in ALL_BUCKET_CONFIG]
        if bad:
            sys.stderr.write(f"unknown --bucket-config item(s): {', '.join(bad)}\n")
            return 2
    if args.workers < 1:
        sys.stderr.write("--workers must be >= 1\n")
        return 2

    verify: Any = False if args.insecure else (args.ca_bundle or True)
    src = Endpoint("source", args.src_endpoint, args.src_access_key, args.src_secret_key, args.src_region, verify)
    dst = Endpoint("destination", args.dst_endpoint, args.dst_access_key, args.dst_secret_key, args.dst_region, verify)

    started = time.monotonic()
    try:
        migrator = Migrator(args, src, dst)
        stats = migrator.run()
    except KeyboardInterrupt:
        LOG.error("interrupted")
        return 130
    except ClientError as exc:
        LOG.error("%s", exc)
        return 1

    elapsed = time.monotonic() - started
    rate = stats.bytes / elapsed if elapsed > 0 else 0
    verb = "would copy" if args.dry_run else "copied"
    LOG.warning(
        "summary: %s %d objects (%s), %d skipped, %d delete markers, %d failed in %.1fs (%s/s)",
        verb, stats.copied, human(stats.bytes), stats.skipped, stats.deleted, stats.failed, elapsed, human(rate),
    )
    if args.failed_list and stats.failures:
        with open(args.failed_list, "w", encoding="utf-8") as fh:
            fh.write("\n".join(stats.failures) + "\n")
        LOG.warning("failures written to %s", args.failed_list)
    if stats.failed:
        for line in stats.failures[:20]:
            LOG.warning("  %s", line)
        if len(stats.failures) > 20:
            LOG.warning("  ... and %d more", len(stats.failures) - 20)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
