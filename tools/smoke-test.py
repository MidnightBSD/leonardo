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
End-to-end smoke test for a running Leonardo server, driven through boto3.

The script creates a few throw-away buckets, exercises the operations real
clients lean on (bucket configuration, objects with awkward keys, listings and
pagination, copies, conditional GETs, tagging, multipart in every flavour,
checksums, presigned URLs, versioning, SigV2, auth error codes, concurrency)
and then removes everything it created. Every check prints PASS or FAIL with
the S3 error code when one is returned; the exit status is non-zero when any
check failed.

It is meant to be run against a packaged install (for example before tagging
a release, with the shipped sample configuration and fsync enabled) because
the unit and conformance suites run the server in-process with a test
profile and cannot see configuration or packaging mistakes.

Requires Python 3.9+ and boto3 (pip install boto3). The CRC32C check also
needs botocore[crt] and is skipped without it.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime
import hashlib
import io
import json
import os
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request
import uuid

try:
    import boto3
    import botocore
    from boto3.s3.transfer import TransferConfig
    from botocore.config import Config
    from botocore.exceptions import ClientError
except ImportError:  # pragma: no cover
    sys.stderr.write("boto3 is required: pip install boto3\n")
    sys.exit(2)


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

class Report:
    def __init__(self, verbose: bool) -> None:
        self.results: list[tuple[str, str, str]] = []
        self.verbose = verbose
        self.section_name = ""

    def section(self, name: str) -> None:
        self.section_name = name
        print(f"\n=== {name}", flush=True)

    def rec(self, status: str, name: str, detail: str = "") -> None:
        self.results.append((status, f"{self.section_name}: {name}", detail))
        if status != "PASS" or self.verbose:
            line = f"{status:4} {name}"
            if detail:
                line += f"  [{detail}]"
            print(line, flush=True)
        else:
            print(f"PASS {name}", flush=True)

    def check(self, name, fn, verify=None):
        """Runs fn; PASS unless it raises or verify() returns a non-True value."""
        try:
            r = fn()
            if verify is not None:
                ok = verify(r)
                if ok is True or ok is None:
                    self.rec("PASS", name)
                else:
                    self.rec("FAIL", name, f"verify: {_short(ok)}")
            else:
                self.rec("PASS", name)
            return r
        except ClientError as e:
            er = e.response.get("Error", {})
            self.rec("FAIL", name, f"{er.get('Code')} {_short(er.get('Message'))} "
                                   f"HTTP {e.response['ResponseMetadata'].get('HTTPStatusCode')}")
        except Exception as e:  # noqa: BLE001 - a smoke test reports, it does not crash
            if "crt" in str(e).lower() and "checksum" in name.lower():
                self.rec("SKIP", name, "needs botocore[crt]")
            else:
                self.rec("FAIL", name, f"{type(e).__name__}: {_short(e)}")
                if self.verbose:
                    traceback.print_exc()
        return None

    def expect_err(self, name, fn, code, http=None):
        """PASS only when fn raises a ClientError with the given S3 code (and HTTP status)."""
        try:
            fn()
            self.rec("FAIL", name, f"expected {code} but the call succeeded")
        except ClientError as e:
            er = e.response.get("Error", {})
            got = er.get("Code")
            st = e.response["ResponseMetadata"].get("HTTPStatusCode")
            if got == code and (http is None or st == http):
                self.rec("PASS", name, f"{got} {st}")
            else:
                self.rec("FAIL", name, f"expected {code}/{http or '*'} got {got} {st}: {_short(er.get('Message'))}")
        except Exception as e:  # noqa: BLE001
            self.rec("FAIL", name, f"{type(e).__name__}: {_short(e)}")

    def summary(self) -> int:
        passed = sum(1 for s, _, _ in self.results if s == "PASS")
        skipped = [(n, d) for s, n, d in self.results if s == "SKIP"]
        failed = [(n, d) for s, n, d in self.results if s == "FAIL"]
        print(f"\n=== SUMMARY\nPASS {passed}  FAIL {len(failed)}  SKIP {len(skipped)}")
        for n, d in skipped:
            print(f"  SKIP {n}: {d}")
        for n, d in failed:
            print(f"  FAIL {n}: {d}")
        return 1 if failed else 0


def _short(value, limit: int = 200) -> str:
    text = repr(value) if not isinstance(value, str) else value
    return text if len(text) <= limit else text[:limit] + "..."


def md5(b: bytes) -> str:
    """S3 ETags are MD5 digests; this is an integrity check, not a security use."""
    return hashlib.md5(b, usedforsecurity=False).hexdigest()


def body(r) -> bytes:
    return r["Body"].read()


def fetch(url, method="GET", data=None, headers=None):
    req = urllib.request.Request(url, method=method, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


# ---------------------------------------------------------------------------
# The test itself
# ---------------------------------------------------------------------------

class Smoke:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.rep = Report(args.verbose)
        self.endpoint = args.endpoint
        self.ak = args.access_key
        self.sk = args.secret_key
        self.region = args.region
        self.s3 = self.client()
        run_id = uuid.uuid4().hex[:8]
        self.B = f"{args.prefix}-main-{run_id}"
        self.B2 = f"{args.prefix}-copy-{run_id}"
        self.BV = f"{args.prefix}-ver-{run_id}"
        self.buckets = (self.B, self.B2, self.BV)
        self.tmpdir = tempfile.TemporaryDirectory(prefix="leonardo-smoke-")

    def client(self, sig="s3v4", ak=None, sk=None, **extra):
        s3cfg = {"addressing_style": "path"}
        s3cfg.update(extra.pop("s3", {}))
        return boto3.client(
            "s3", endpoint_url=self.endpoint, region_name=self.region,
            aws_access_key_id=ak or self.ak, aws_secret_access_key=sk or self.sk,
            verify=False if self.args.insecure else (self.args.ca_bundle or None),
            config=Config(signature_version=sig, s3=s3cfg, retries={"max_attempts": 1}, **extra))

    # -- sections ---------------------------------------------------------

    def buckets_section(self):
        s3, B, B2, BV, rep = self.s3, self.B, self.B2, self.BV, self.rep
        rep.section("buckets")
        rep.check("create bucket", lambda: s3.create_bucket(Bucket=B))
        rep.check("create bucket #2", lambda: s3.create_bucket(Bucket=B2))
        rep.check("create bucket (versioned later)", lambda: s3.create_bucket(Bucket=BV))
        rep.check("head_bucket", lambda: s3.head_bucket(Bucket=B))
        rep.check("get_bucket_location", lambda: s3.get_bucket_location(Bucket=B),
                  lambda r: r.get("LocationConstraint") in (None, self.region) or r.get("LocationConstraint"))
        rep.check("list_buckets contains new bucket", lambda: s3.list_buckets(),
                  lambda r: B in [b["Name"] for b in r["Buckets"]] or [b["Name"] for b in r["Buckets"]])
        rep.expect_err("create bucket invalid name", lambda: s3.create_bucket(Bucket="Bad_Name"), "InvalidBucketName", 400)
        rep.expect_err("create bucket duplicate", lambda: s3.create_bucket(Bucket=B), "BucketAlreadyOwnedByYou", 409)
        rep.expect_err("head nonexistent bucket", lambda: s3.head_bucket(Bucket=f"{B}-does-not-exist"), "404", 404)
        rep.expect_err("list objects nonexistent bucket", lambda: s3.list_objects_v2(Bucket=f"{B}-does-not-exist"), "NoSuchBucket", 404)
        rep.expect_err("get_bucket_tagging on untagged bucket", lambda: s3.get_bucket_tagging(Bucket=B), "NoSuchTagSet", 404)
        rep.check("put_bucket_tagging", lambda: s3.put_bucket_tagging(Bucket=B, Tagging={"TagSet": [{"Key": "env", "Value": "test"}, {"Key": "team", "Value": "ops"}]}))
        rep.check("get_bucket_tagging", lambda: s3.get_bucket_tagging(Bucket=B),
                  lambda r: sorted((t["Key"], t["Value"]) for t in r["TagSet"]) == [("env", "test"), ("team", "ops")] or r["TagSet"])
        rep.check("delete_bucket_tagging", lambda: s3.delete_bucket_tagging(Bucket=B))
        rep.expect_err("get_bucket_tagging after delete", lambda: s3.get_bucket_tagging(Bucket=B), "NoSuchTagSet", 404)
        rep.check("put_bucket_cors", lambda: s3.put_bucket_cors(Bucket=B, CORSConfiguration={"CORSRules": [{"AllowedOrigins": ["https://example.com"], "AllowedMethods": ["GET", "PUT"], "AllowedHeaders": ["*"], "MaxAgeSeconds": 3000}]}))
        rep.check("get_bucket_cors", lambda: s3.get_bucket_cors(Bucket=B),
                  lambda r: r["CORSRules"][0]["AllowedOrigins"] == ["https://example.com"] or r["CORSRules"])
        rep.check("delete_bucket_cors", lambda: s3.delete_bucket_cors(Bucket=B))
        policy = {"Version": "2012-10-17", "Statement": [{"Sid": "ro", "Effect": "Allow", "Principal": "*",
                  "Action": ["s3:GetObject"], "Resource": [f"arn:aws:s3:::{B}/public/*"]}]}
        rep.check("put_bucket_policy", lambda: s3.put_bucket_policy(Bucket=B, Policy=json.dumps(policy)))
        rep.check("get_bucket_policy", lambda: s3.get_bucket_policy(Bucket=B),
                  lambda r: json.loads(r["Policy"])["Statement"][0]["Sid"] == "ro" or r["Policy"])
        rep.check("delete_bucket_policy", lambda: s3.delete_bucket_policy(Bucket=B))
        rep.check("put_bucket_lifecycle_configuration", lambda: s3.put_bucket_lifecycle_configuration(Bucket=B, LifecycleConfiguration={"Rules": [{"ID": "expire-tmp", "Status": "Enabled", "Filter": {"Prefix": "tmp/"}, "Expiration": {"Days": 7}}]}))
        rep.check("get_bucket_lifecycle_configuration", lambda: s3.get_bucket_lifecycle_configuration(Bucket=B),
                  lambda r: r["Rules"][0]["ID"] == "expire-tmp" or r["Rules"])
        rep.check("delete_bucket_lifecycle", lambda: s3.delete_bucket_lifecycle(Bucket=B))
        rep.check("get_bucket_acl", lambda: s3.get_bucket_acl(Bucket=B), lambda r: "Owner" in r and "Grants" in r)
        rep.check("put_bucket_acl private", lambda: s3.put_bucket_acl(Bucket=B, ACL="private"))
        rep.check("put_public_access_block", lambda: s3.put_public_access_block(Bucket=B, PublicAccessBlockConfiguration={"BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True, "RestrictPublicBuckets": True}))
        rep.check("get_public_access_block", lambda: s3.get_public_access_block(Bucket=B),
                  lambda r: r["PublicAccessBlockConfiguration"]["BlockPublicAcls"] is True)
        rep.check("delete_public_access_block", lambda: s3.delete_public_access_block(Bucket=B))
        rep.check("get_bucket_versioning (unset)", lambda: s3.get_bucket_versioning(Bucket=B),
                  lambda r: "Status" not in r or r.get("Status"))

    SPECIAL_KEYS = ["dir/sub/file.txt", "dir/sub/deeper/x.txt", "dir/other.txt", "with space.txt",
                    "plus+sign.txt", "unicode-ñ-日本.txt", "a&b=c:d@e.txt", "tilde~and(parens).txt",
                    "percent%20literal.txt", "trailing-slash-dir/"]

    def objects_section(self):
        s3, B, rep = self.s3, self.B, self.rep
        rep.section("objects")
        data = self.data = b"hello, leonardo smoke test\n" * 10
        rep.check("put_object with headers+metadata",
                  lambda: s3.put_object(Bucket=B, Key="docs/readme.txt", Body=data, ContentType="text/plain; charset=utf-8",
                                        Metadata={"owner": "smoke", "purpose": "test"}, CacheControl="max-age=60",
                                        ContentDisposition='attachment; filename="readme.txt"', ContentLanguage="en"),
                  lambda r: r["ETag"] == f'"{md5(data)}"' or r["ETag"])
        rep.check("head_object headers+metadata", lambda: s3.head_object(Bucket=B, Key="docs/readme.txt"),
                  lambda r: (r["ContentLength"] == len(data) and r["ContentType"] == "text/plain; charset=utf-8"
                             and r["Metadata"] == {"owner": "smoke", "purpose": "test"} and r.get("CacheControl") == "max-age=60"
                             and r.get("ContentDisposition") == 'attachment; filename="readme.txt"' and r.get("ContentLanguage") == "en"
                             and r["ETag"] == f'"{md5(data)}"')
                  or {k: r.get(k) for k in ("ContentLength", "ContentType", "Metadata", "CacheControl", "ContentDisposition", "ContentLanguage", "ETag")})
        rep.check("get_object body+headers", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt"),
                  lambda r: (r["ContentType"] == "text/plain; charset=utf-8" and r["Metadata"]["owner"] == "smoke" and body(r) == data) or "mismatch")
        rep.check("get_object Range bytes=7-14", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", Range="bytes=7-14"),
                  lambda r: (r["ResponseMetadata"]["HTTPStatusCode"] == 206 and body(r) == data[7:15]
                             and r.get("ContentRange") == f"bytes 7-14/{len(data)}")
                  or (r["ResponseMetadata"]["HTTPStatusCode"], r.get("ContentRange")))
        rep.check("get_object Range suffix bytes=-5", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", Range="bytes=-5"),
                  lambda r: body(r) == data[-5:] or "mismatch")
        rep.check("get_object Range open bytes=250-", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", Range="bytes=250-"),
                  lambda r: body(r) == data[250:] or "mismatch")
        rep.expect_err("get_object Range unsatisfiable", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", Range="bytes=99999-"), "InvalidRange", 416)
        rep.check("put_object empty (0 bytes)", lambda: s3.put_object(Bucket=B, Key="empty.bin", Body=b""))
        rep.check("head empty object", lambda: s3.head_object(Bucket=B, Key="empty.bin"), lambda r: r["ContentLength"] == 0 or r["ContentLength"])
        rep.check("get empty object", lambda: s3.get_object(Bucket=B, Key="empty.bin"), lambda r: body(r) == b"" or "nonempty")
        rep.check("put_object no ContentType (default)", lambda: s3.put_object(Bucket=B, Key="noct.bin", Body=b"\x00\x01\x02"))
        rep.check("head noct.bin ContentType", lambda: s3.head_object(Bucket=B, Key="noct.bin"),
                  lambda r: r["ContentType"] in ("binary/octet-stream", "application/octet-stream") or r["ContentType"])
        for k in self.SPECIAL_KEYS:
            payload = ("key:" + k).encode()
            rep.check(f"put special key {k!r}", lambda k=k, p=payload: s3.put_object(Bucket=B, Key=k, Body=p))
            rep.check(f"get special key {k!r}", lambda k=k: s3.get_object(Bucket=B, Key=k),
                      lambda r, p=payload: body(r) == p or "mismatch")
            rep.check(f"head special key {k!r}", lambda k=k: s3.head_object(Bucket=B, Key=k))
        rep.expect_err("head 'trailing-slash-dir' without the slash is a different key",
                       lambda: s3.head_object(Bucket=B, Key="trailing-slash-dir"), "404", 404)

    def listing_section(self):
        s3, B, rep = self.s3, self.B, self.rep
        rep.section("listing")
        for i in range(25):
            s3.put_object(Bucket=B, Key=f"many/obj-{i:03d}.txt", Body=f"{i}".encode())
        expected = set(self.SPECIAL_KEYS) | {"docs/readme.txt", "empty.bin", "noct.bin"}
        rep.check("list_objects_v2 all keys present", lambda: s3.list_objects_v2(Bucket=B),
                  lambda r: expected <= {o["Key"] for o in r["Contents"]} or sorted(expected - {o["Key"] for o in r["Contents"]}))
        rep.check("list_objects_v2 keeps trailing-slash key", lambda: s3.list_objects_v2(Bucket=B, Prefix="trailing-slash-dir"),
                  lambda r: [o["Key"] for o in r.get("Contents", [])] == ["trailing-slash-dir/"] or [o["Key"] for o in r.get("Contents", [])])
        rep.check("list_objects_v2 Prefix+Delimiter", lambda: s3.list_objects_v2(Bucket=B, Prefix="dir/", Delimiter="/"),
                  lambda r: ([o["Key"] for o in r.get("Contents", [])] == ["dir/other.txt"]
                             and [p["Prefix"] for p in r.get("CommonPrefixes", [])] == ["dir/sub/"])
                  or (r.get("Contents"), r.get("CommonPrefixes")))
        rep.check("list_objects_v2 root Delimiter", lambda: s3.list_objects_v2(Bucket=B, Delimiter="/"),
                  lambda r: {"dir/", "docs/", "many/", "trailing-slash-dir/"} <= {p["Prefix"] for p in r.get("CommonPrefixes", [])}
                  or [p["Prefix"] for p in r.get("CommonPrefixes", [])])

        def page_v2():
            keys, tok, pages = [], None, 0
            while True:
                kw = {"Bucket": B, "Prefix": "many/", "MaxKeys": 10}
                if tok:
                    kw["ContinuationToken"] = tok
                r = s3.list_objects_v2(**kw)
                pages += 1
                keys += [o["Key"] for o in r.get("Contents", [])]
                if not r.get("IsTruncated"):
                    return keys, pages
                tok = r["NextContinuationToken"]

        def page_v1():
            keys, marker, pages = [], None, 0
            while True:
                kw = {"Bucket": B, "Prefix": "many/", "MaxKeys": 10}
                if marker:
                    kw["Marker"] = marker
                r = s3.list_objects(**kw)
                pages += 1
                keys += [o["Key"] for o in r.get("Contents", [])]
                if not r.get("IsTruncated"):
                    return keys, pages
                marker = r.get("NextMarker") or keys[-1]

        want = [f"many/obj-{i:03d}.txt" for i in range(25)]
        rep.check("list_objects_v2 pagination MaxKeys=10", page_v2, lambda r: (r[0] == want and r[1] == 3) or (len(r[0]), r[1]))
        rep.check("list_objects_v2 StartAfter", lambda: s3.list_objects_v2(Bucket=B, Prefix="many/", StartAfter="many/obj-020.txt"),
                  lambda r: [o["Key"] for o in r["Contents"]] == want[21:] or [o["Key"] for o in r["Contents"]])
        rep.check("list_objects_v2 EncodingType=url", lambda: s3.list_objects_v2(Bucket=B, Prefix="with", EncodingType="url"),
                  lambda r: [o["Key"] for o in r["Contents"]] == ["with space.txt"] or [o["Key"] for o in r["Contents"]])
        rep.check("list_objects v1 pagination", page_v1, lambda r: (r[0] == want and r[1] == 3) or (len(r[0]), r[1]))
        rep.check("list_objects_v2 omits Owner by default", lambda: s3.list_objects_v2(Bucket=B, Prefix="docs/"),
                  lambda r: "Owner" not in r["Contents"][0] or r["Contents"][0])
        rep.check("list_objects_v2 FetchOwner", lambda: s3.list_objects_v2(Bucket=B, Prefix="docs/", FetchOwner=True),
                  lambda r: "Owner" in r["Contents"][0] or r["Contents"][0])
        rep.check("list_objects v1 includes Owner", lambda: s3.list_objects(Bucket=B, Prefix="docs/"),
                  lambda r: "Owner" in r["Contents"][0] or r["Contents"][0])
        rep.check("list_object_versions on unversioned bucket uses VersionId 'null'",
                  lambda: s3.list_object_versions(Bucket=B, Prefix="docs/readme.txt"),
                  lambda r: [v.get("VersionId") for v in r.get("Versions", [])] == ["null"] or r.get("Versions"))
        rep.check("head_object VersionId='null' on unversioned object",
                  lambda: s3.head_object(Bucket=B, Key="docs/readme.txt", VersionId="null"),
                  lambda r: r["ContentLength"] == len(self.data) or r["ContentLength"])

    def copy_section(self):
        s3, B, B2, rep, data = self.s3, self.B, self.B2, self.rep, self.data
        rep.section("copy / conditional")
        rep.check("copy_object same bucket (COPY metadata)",
                  lambda: s3.copy_object(Bucket=B, Key="docs/readme-copy.txt", CopySource={"Bucket": B, "Key": "docs/readme.txt"}),
                  lambda r: r["CopyObjectResult"]["ETag"] == f'"{md5(data)}"' or r["CopyObjectResult"])
        rep.check("head copy preserved metadata+content-type", lambda: s3.head_object(Bucket=B, Key="docs/readme-copy.txt"),
                  lambda r: (r["Metadata"] == {"owner": "smoke", "purpose": "test"} and r["ContentType"] == "text/plain; charset=utf-8"
                             and r.get("CacheControl") == "max-age=60") or {k: r.get(k) for k in ("Metadata", "ContentType", "CacheControl")})
        rep.check("copy_object REPLACE metadata",
                  lambda: s3.copy_object(Bucket=B, Key="docs/readme-replaced.txt", CopySource={"Bucket": B, "Key": "docs/readme.txt"},
                                         MetadataDirective="REPLACE", ContentType="application/x-custom", Metadata={"new": "meta"}))
        rep.check("head replaced metadata", lambda: s3.head_object(Bucket=B, Key="docs/readme-replaced.txt"),
                  lambda r: (r["Metadata"] == {"new": "meta"} and r["ContentType"] == "application/x-custom")
                  or {k: r.get(k) for k in ("Metadata", "ContentType")})
        rep.check("copy_object cross bucket", lambda: s3.copy_object(Bucket=B2, Key="copied/readme.txt", CopySource={"Bucket": B, "Key": "docs/readme.txt"}))
        rep.check("get cross-bucket copy", lambda: s3.get_object(Bucket=B2, Key="copied/readme.txt"), lambda r: body(r) == data or "mismatch")
        rep.check("copy_object special source key", lambda: s3.copy_object(Bucket=B2, Key="copied/space.txt", CopySource={"Bucket": B, "Key": "with space.txt"}))
        rep.check("copy_object onto itself with REPLACE (metadata update)",
                  lambda: s3.copy_object(Bucket=B, Key="docs/readme-copy.txt", CopySource={"Bucket": B, "Key": "docs/readme-copy.txt"},
                                         MetadataDirective="REPLACE", Metadata={"updated": "yes"}, ContentType="text/plain"))
        rep.check("head after in-place metadata update", lambda: s3.head_object(Bucket=B, Key="docs/readme-copy.txt"),
                  lambda r: r["Metadata"] == {"updated": "yes"} or r["Metadata"])
        rep.expect_err("copy_object onto itself without REPLACE",
                       lambda: s3.copy_object(Bucket=B, Key="docs/readme-copy.txt", CopySource={"Bucket": B, "Key": "docs/readme-copy.txt"}), "InvalidRequest", 400)
        rep.expect_err("copy_object missing source", lambda: s3.copy_object(Bucket=B, Key="x", CopySource={"Bucket": B, "Key": "nope.txt"}), "NoSuchKey", 404)
        etag = f'"{md5(data)}"'
        rep.expect_err("get IfNoneMatch matching etag -> 304", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", IfNoneMatch=etag), "304", 304)
        rep.expect_err("get IfMatch wrong etag -> 412", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", IfMatch='"deadbeef"'), "PreconditionFailed", 412)
        rep.check("get IfMatch correct etag", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", IfMatch=etag), lambda r: body(r) == data or "mismatch")
        future = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)
        rep.expect_err("get IfModifiedSince future -> 304", lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", IfModifiedSince=future), "304", 304)
        rep.expect_err("head IfNoneMatch matching -> 304", lambda: s3.head_object(Bucket=B, Key="docs/readme.txt", IfNoneMatch=etag), "304", 304)
        rep.check("get_object response-* overrides",
                  lambda: s3.get_object(Bucket=B, Key="docs/readme.txt", ResponseContentType="text/x-override",
                                        ResponseContentDisposition="inline", ResponseCacheControl="no-store"),
                  lambda r: (r["ContentType"] == "text/x-override" and r.get("ContentDisposition") == "inline" and r.get("CacheControl") == "no-store")
                  or (r["ContentType"], r.get("ContentDisposition"), r.get("CacheControl")))

    def tagging_section(self):
        s3, B, rep, data = self.s3, self.B, self.rep, self.data
        rep.section("tagging / acl / attributes")
        rep.check("put_object_tagging", lambda: s3.put_object_tagging(Bucket=B, Key="docs/readme.txt", Tagging={"TagSet": [{"Key": "k1", "Value": "v1"}, {"Key": "k2", "Value": "v 2"}]}))
        rep.check("get_object_tagging", lambda: s3.get_object_tagging(Bucket=B, Key="docs/readme.txt"),
                  lambda r: sorted((t["Key"], t["Value"]) for t in r["TagSet"]) == [("k1", "v1"), ("k2", "v 2")] or r["TagSet"])
        rep.check("put_object with Tagging header", lambda: s3.put_object(Bucket=B, Key="tagged.txt", Body=b"t", Tagging="a=1&b=two"))
        rep.check("get_object_tagging from header", lambda: s3.get_object_tagging(Bucket=B, Key="tagged.txt"),
                  lambda r: sorted((t["Key"], t["Value"]) for t in r["TagSet"]) == [("a", "1"), ("b", "two")] or r["TagSet"])
        rep.check("head shows TagCount", lambda: s3.head_object(Bucket=B, Key="tagged.txt"), lambda r: r.get("TagCount") == 2 or r.get("TagCount"))
        rep.check("copy_object TaggingDirective=REPLACE",
                  lambda: s3.copy_object(Bucket=B, Key="tagged-copy.txt", CopySource={"Bucket": B, "Key": "tagged.txt"},
                                         TaggingDirective="REPLACE", Tagging="only=this"))
        rep.check("get_object_tagging after REPLACE", lambda: s3.get_object_tagging(Bucket=B, Key="tagged-copy.txt"),
                  lambda r: [(t["Key"], t["Value"]) for t in r["TagSet"]] == [("only", "this")] or r["TagSet"])
        rep.check("delete_object_tagging", lambda: s3.delete_object_tagging(Bucket=B, Key="tagged.txt"))
        rep.check("get_object_tagging after delete", lambda: s3.get_object_tagging(Bucket=B, Key="tagged.txt"), lambda r: r["TagSet"] == [] or r["TagSet"])
        rep.check("get_object_acl", lambda: s3.get_object_acl(Bucket=B, Key="docs/readme.txt"), lambda r: "Grants" in r)
        rep.check("put_object_acl private", lambda: s3.put_object_acl(Bucket=B, Key="docs/readme.txt", ACL="private"))
        rep.check("put_object_acl public-read", lambda: s3.put_object_acl(Bucket=B, Key="docs/readme.txt", ACL="public-read"))
        rep.check("get_object_attributes", lambda: s3.get_object_attributes(Bucket=B, Key="docs/readme.txt", ObjectAttributes=["ETag", "ObjectSize", "StorageClass"]),
                  lambda r: r.get("ObjectSize") == len(data) or r)

    def delete_section(self):
        s3, B, rep = self.s3, self.B, self.rep
        rep.section("delete")
        rep.check("delete_object", lambda: s3.delete_object(Bucket=B, Key="noct.bin"),
                  lambda r: r["ResponseMetadata"]["HTTPStatusCode"] == 204 or r["ResponseMetadata"]["HTTPStatusCode"])
        rep.expect_err("get deleted object", lambda: s3.get_object(Bucket=B, Key="noct.bin"), "NoSuchKey", 404)
        rep.expect_err("head deleted object", lambda: s3.head_object(Bucket=B, Key="noct.bin"), "404", 404)
        rep.check("delete_object nonexistent is 204", lambda: s3.delete_object(Bucket=B, Key="never-existed"),
                  lambda r: r["ResponseMetadata"]["HTTPStatusCode"] == 204 or r["ResponseMetadata"]["HTTPStatusCode"])
        rep.check("delete trailing-slash key", lambda: s3.delete_object(Bucket=B, Key="trailing-slash-dir/"))
        rep.check("trailing-slash key gone from listing", lambda: s3.list_objects_v2(Bucket=B, Prefix="trailing-slash-dir"),
                  lambda r: r["KeyCount"] == 0 or [o["Key"] for o in r["Contents"]])
        rep.check("delete_objects batch",
                  lambda: s3.delete_objects(Bucket=B, Delete={"Objects": [{"Key": f"many/obj-{i:03d}.txt"} for i in range(20, 25)] + [{"Key": "ghost.txt"}], "Quiet": False}),
                  lambda r: (len(r.get("Deleted", [])) == 6 and not r.get("Errors")) or (r.get("Deleted"), r.get("Errors")))
        rep.check("delete_objects Quiet", lambda: s3.delete_objects(Bucket=B, Delete={"Objects": [{"Key": "many/obj-019.txt"}], "Quiet": True}),
                  lambda r: not r.get("Errors") or r.get("Errors"))
        rep.check("list after batch delete", lambda: s3.list_objects_v2(Bucket=B, Prefix="many/"), lambda r: r["KeyCount"] == 19 or r["KeyCount"])

    def multipart_section(self):
        s3, B, B2, rep = self.s3, self.B, self.B2, self.rep
        rep.section("large / multipart")
        mib = 1024 * 1024
        part = self.args.part_size_mib * mib
        big = os.urandom(4 * part)
        rep.check(f"put_object {len(big) // mib} MiB single PUT", lambda: s3.put_object(Bucket=B, Key="big/big.bin", Body=big),
                  lambda r: r["ETag"] == f'"{md5(big)}"' or r["ETag"])
        rep.check("get big object and compare", lambda: s3.get_object(Bucket=B, Key="big/big.bin"), lambda r: md5(body(r)) == md5(big) or "md5 mismatch")
        rep.check("range on big object tail", lambda: s3.get_object(Bucket=B, Key="big/big.bin", Range=f"bytes={len(big) - 1000}-"),
                  lambda r: body(r) == big[-1000:] or "mismatch")
        rep.check("put_object from file-like stream", lambda: s3.put_object(Bucket=B, Key="big/stream.bin", Body=io.BytesIO(big[:3 * mib])),
                  lambda r: r["ETag"] == f'"{md5(big[:3 * mib])}"' or r["ETag"])
        tmpf = os.path.join(self.tmpdir.name, "upload.bin")
        payload = os.urandom(2 * part + part // 2)
        with open(tmpf, "wb") as fh:
            fh.write(payload)
        tc = TransferConfig(multipart_threshold=part, multipart_chunksize=part, max_concurrency=4)
        rep.check("upload_file multipart (3 parts, concurrent)", lambda: s3.upload_file(tmpf, B, "big/upload.bin", Config=tc))
        rep.check("head multipart object ETag has -3", lambda: s3.head_object(Bucket=B, Key="big/upload.bin"),
                  lambda r: (r["ContentLength"] == len(payload) and r["ETag"].endswith('-3"')) or (r["ContentLength"], r["ETag"]))
        dl = tmpf + ".dl"
        rep.check("download_file multipart ranged", lambda: s3.download_file(B, "big/upload.bin", dl, Config=tc))
        rep.check("downloaded content matches", lambda: open(dl, "rb").read(), lambda r: md5(r) == md5(payload) or "md5 mismatch")

        p1 = os.urandom(part)
        p2 = b"tail-part" * 1000

        def manual_mpu():
            u = s3.create_multipart_upload(Bucket=B, Key="big/manual.bin", ContentType="application/x-manual", Metadata={"via": "manual"})
            uid = u["UploadId"]
            e1 = s3.upload_part(Bucket=B, Key="big/manual.bin", UploadId=uid, PartNumber=1, Body=p1)["ETag"]
            e2 = s3.upload_part(Bucket=B, Key="big/manual.bin", UploadId=uid, PartNumber=2, Body=p2)["ETag"]
            lp = s3.list_parts(Bucket=B, Key="big/manual.bin", UploadId=uid)
            assert [p["PartNumber"] for p in lp["Parts"]] == [1, 2], lp["Parts"]
            assert lp["Parts"][0]["Size"] == len(p1), lp["Parts"][0]
            lu = s3.list_multipart_uploads(Bucket=B)
            assert uid in [x["UploadId"] for x in lu.get("Uploads", [])], lu.get("Uploads")
            return s3.complete_multipart_upload(Bucket=B, Key="big/manual.bin", UploadId=uid,
                                                MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": e1}, {"PartNumber": 2, "ETag": e2}]})

        rep.check("manual multipart create/upload/list/complete", manual_mpu, lambda r: r["ETag"].endswith('-2"') or r)
        rep.check("get manual multipart content", lambda: s3.get_object(Bucket=B, Key="big/manual.bin"),
                  lambda r: (body(r) == p1 + p2 and r["ContentType"] == "application/x-manual" and r["Metadata"] == {"via": "manual"})
                  or (r["ContentType"], r["Metadata"]))
        rep.check("list_multipart_uploads empty after complete", lambda: s3.list_multipart_uploads(Bucket=B),
                  lambda r: not r.get("Uploads") or [x["Key"] for x in r["Uploads"]])

        def abort_flow():
            uid = s3.create_multipart_upload(Bucket=B, Key="big/aborted.bin")["UploadId"]
            s3.upload_part(Bucket=B, Key="big/aborted.bin", UploadId=uid, PartNumber=1, Body=b"x" * 1024)
            s3.abort_multipart_upload(Bucket=B, Key="big/aborted.bin", UploadId=uid)
            lu = s3.list_multipart_uploads(Bucket=B)
            assert uid not in [x["UploadId"] for x in lu.get("Uploads", [])]
            return uid

        uid_ab = rep.check("abort multipart", abort_flow)
        rep.expect_err("complete after abort -> NoSuchUpload",
                       lambda: s3.complete_multipart_upload(Bucket=B, Key="big/aborted.bin", UploadId=uid_ab or "0123456789ABCDEFGHJKMNPQRS",
                                                            MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": '"x"'}]}), "NoSuchUpload", 404)
        rep.expect_err("upload_part bogus uploadId", lambda: s3.upload_part(Bucket=B, Key="big/x.bin", UploadId="not-a-real-upload-id", PartNumber=1, Body=b"x"), "NoSuchUpload", 404)
        rep.expect_err("head aborted key -> 404", lambda: s3.head_object(Bucket=B, Key="big/aborted.bin"), "404", 404)

        def bad_etag():
            uid = s3.create_multipart_upload(Bucket=B, Key="big/badetag.bin")["UploadId"]
            e1 = s3.upload_part(Bucket=B, Key="big/badetag.bin", UploadId=uid, PartNumber=1, Body=p1)["ETag"]
            try:
                s3.complete_multipart_upload(Bucket=B, Key="big/badetag.bin", UploadId=uid,
                                             MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": '"00000000000000000000000000000000"'}]})
            except ClientError:
                # the rejected completion must leave the upload intact
                parts = s3.list_parts(Bucket=B, Key="big/badetag.bin", UploadId=uid)["Parts"]
                assert [p["ETag"] for p in parts] == [e1], parts
                s3.abort_multipart_upload(Bucket=B, Key="big/badetag.bin", UploadId=uid)
                raise

        rep.expect_err("complete with wrong part ETag -> InvalidPart (upload survives)", bad_etag, "InvalidPart", 400)

        def too_small():
            uid = s3.create_multipart_upload(Bucket=B, Key="big/small.bin")["UploadId"]
            e1 = s3.upload_part(Bucket=B, Key="big/small.bin", UploadId=uid, PartNumber=1, Body=b"tiny")["ETag"]
            e2 = s3.upload_part(Bucket=B, Key="big/small.bin", UploadId=uid, PartNumber=2, Body=b"tiny2")["ETag"]
            try:
                s3.complete_multipart_upload(Bucket=B, Key="big/small.bin", UploadId=uid,
                                             MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": e1}, {"PartNumber": 2, "ETag": e2}]})
            finally:
                s3.abort_multipart_upload(Bucket=B, Key="big/small.bin", UploadId=uid)

        rep.expect_err("complete with non-last part below minimum -> EntityTooSmall", too_small, "EntityTooSmall", 400)

        def part_copy():
            uid = s3.create_multipart_upload(Bucket=B, Key="big/partcopy.bin")["UploadId"]
            src = {"Bucket": B, "Key": "big/big.bin"}
            c1 = s3.upload_part_copy(Bucket=B, Key="big/partcopy.bin", UploadId=uid, PartNumber=1, CopySource=src,
                                     CopySourceRange=f"bytes=0-{part - 1}")["CopyPartResult"]["ETag"]
            c2 = s3.upload_part_copy(Bucket=B, Key="big/partcopy.bin", UploadId=uid, PartNumber=2, CopySource=src,
                                     CopySourceRange=f"bytes={part}-{part + mib - 1}")["CopyPartResult"]["ETag"]
            sizes = [p["Size"] for p in s3.list_parts(Bucket=B, Key="big/partcopy.bin", UploadId=uid)["Parts"]]
            assert sizes == [part, mib], sizes
            s3.complete_multipart_upload(Bucket=B, Key="big/partcopy.bin", UploadId=uid,
                                         MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": c1}, {"PartNumber": 2, "ETag": c2}]})
            return body(s3.get_object(Bucket=B, Key="big/partcopy.bin"))

        rep.check("upload_part_copy with ranges", part_copy, lambda r: r == big[:part + mib] or f"len {len(r)}")

        def part_copy_bad_range():
            uid = s3.create_multipart_upload(Bucket=B, Key="big/partcopy-bad.bin")["UploadId"]
            try:
                s3.upload_part_copy(Bucket=B, Key="big/partcopy-bad.bin", UploadId=uid, PartNumber=1,
                                    CopySource={"Bucket": B, "Key": "big/big.bin"}, CopySourceRange=f"bytes=0-{len(big) * 2}")
            finally:
                s3.abort_multipart_upload(Bucket=B, Key="big/partcopy-bad.bin", UploadId=uid)

        rep.expect_err("upload_part_copy range past end -> InvalidArgument", part_copy_bad_range, "InvalidArgument", 400)
        rep.check("copy_object of big object", lambda: s3.copy_object(Bucket=B2, Key="big-copy.bin", CopySource={"Bucket": B, "Key": "big/big.bin"}),
                  lambda r: r["CopyObjectResult"]["ETag"] == f'"{md5(big)}"' or r["CopyObjectResult"])

    def checksums_section(self):
        s3, B, rep = self.s3, self.B, self.rep
        rep.section("checksums / digests")
        rep.expect_err("put_object with wrong Content-MD5 -> BadDigest",
                       lambda: s3.put_object(Bucket=B, Key="md5.bin", Body=b"abc", ContentMD5=base64.b64encode(hashlib.md5(b"xyz").digest()).decode()), "BadDigest", 400)
        rep.check("put_object with correct Content-MD5",
                  lambda: s3.put_object(Bucket=B, Key="md5.bin", Body=b"abc", ContentMD5=base64.b64encode(hashlib.md5(b"abc").digest()).decode()))
        rep.check("put_object ChecksumAlgorithm=SHA256", lambda: s3.put_object(Bucket=B, Key="sha.bin", Body=b"abc", ChecksumAlgorithm="SHA256"))
        rep.check("put_object ChecksumAlgorithm=CRC32C (checksum)", lambda: s3.put_object(Bucket=B, Key="crc.bin", Body=b"abc", ChecksumAlgorithm="CRC32C"))
        rep.check("get sha.bin ChecksumMode", lambda: s3.get_object(Bucket=B, Key="sha.bin", ChecksumMode="ENABLED"), lambda r: body(r) == b"abc" or "mismatch")

    def presigned_section(self):
        s3, B, rep, data = self.s3, self.B, self.rep, self.data
        rep.section("presigned")
        rep.check("presigned GET", lambda: fetch(s3.generate_presigned_url("get_object", Params={"Bucket": B, "Key": "docs/readme.txt"}, ExpiresIn=300)),
                  lambda r: (r[0] == 200 and r[1] == data) or (r[0], r[1][:200]))
        rep.check("presigned GET special key", lambda: fetch(s3.generate_presigned_url("get_object", Params={"Bucket": B, "Key": "unicode-ñ-日本.txt"}, ExpiresIn=300)),
                  lambda r: r[0] == 200 or (r[0], r[1][:200]))
        rep.check("presigned PUT then GET",
                  lambda: (fetch(s3.generate_presigned_url("put_object", Params={"Bucket": B, "Key": "presigned-put.txt", "ContentType": "text/plain"}, ExpiresIn=300),
                                 "PUT", b"via presigned put", {"Content-Type": "text/plain"}),
                           body(s3.get_object(Bucket=B, Key="presigned-put.txt"))),
                  lambda r: (r[0][0] == 200 and r[1] == b"via presigned put") or (r[0][0], r[0][1][:200]))
        rep.check("presigned DELETE", lambda: fetch(s3.generate_presigned_url("delete_object", Params={"Bucket": B, "Key": "presigned-put.txt"}, ExpiresIn=300), "DELETE"),
                  lambda r: r[0] == 204 or (r[0], r[1][:200]))

        def expired():
            u = s3.generate_presigned_url("get_object", Params={"Bucket": B, "Key": "docs/readme.txt"}, ExpiresIn=1)
            time.sleep(2)
            return fetch(u)

        rep.check("presigned expired -> 403", expired, lambda r: r[0] == 403 or (r[0], r[1][:200]))
        rep.check("presigned tampered signature -> 403 SignatureDoesNotMatch",
                  lambda: fetch(s3.generate_presigned_url("get_object", Params={"Bucket": B, "Key": "docs/readme.txt"}, ExpiresIn=300)[:-4] + "0000"),
                  lambda r: (r[0] == 403 and b"SignatureDoesNotMatch" in r[1]) or (r[0], r[1][:200]))

    def versioning_section(self):
        s3, BV, rep = self.s3, self.BV, self.rep
        rep.section("versioning")
        rep.check("put_bucket_versioning Enabled", lambda: s3.put_bucket_versioning(Bucket=BV, VersioningConfiguration={"Status": "Enabled"}))
        rep.check("get_bucket_versioning Enabled", lambda: s3.get_bucket_versioning(Bucket=BV), lambda r: r.get("Status") == "Enabled" or r.get("Status"))
        v1 = rep.check("put v1", lambda: s3.put_object(Bucket=BV, Key="doc.txt", Body=b"version one"), lambda r: bool(r.get("VersionId")) or "no VersionId")
        v2 = rep.check("put v2", lambda: s3.put_object(Bucket=BV, Key="doc.txt", Body=b"version two"),
                       lambda r: (bool(r.get("VersionId")) and r["VersionId"] != (v1 or {}).get("VersionId")) or "no/same VersionId")
        vid1 = (v1 or {}).get("VersionId")
        vid2 = (v2 or {}).get("VersionId")
        rep.check("get latest is v2", lambda: s3.get_object(Bucket=BV, Key="doc.txt"),
                  lambda r: (body(r) == b"version two" and r.get("VersionId") == vid2) or r.get("VersionId"))
        rep.check("get by VersionId v1", lambda: s3.get_object(Bucket=BV, Key="doc.txt", VersionId=vid1), lambda r: body(r) == b"version one" or "mismatch")
        rep.check("head by VersionId v1", lambda: s3.head_object(Bucket=BV, Key="doc.txt", VersionId=vid1), lambda r: r.get("VersionId") == vid1 or r.get("VersionId"))
        rep.expect_err("get unknown VersionId -> NoSuchVersion", lambda: s3.get_object(Bucket=BV, Key="doc.txt", VersionId="01HXDOESNOTEXIST0000000000"), "NoSuchVersion", 404)
        rep.check("list_object_versions 2 versions", lambda: s3.list_object_versions(Bucket=BV, Prefix="doc.txt"),
                  lambda r: [(v["VersionId"], v["IsLatest"]) for v in r["Versions"]] == [(vid2, True), (vid1, False)]
                  or [(v["VersionId"], v["IsLatest"]) for v in r["Versions"]])
        dm = rep.check("delete without VersionId -> delete marker", lambda: s3.delete_object(Bucket=BV, Key="doc.txt"),
                       lambda r: (r.get("DeleteMarker") is True and bool(r.get("VersionId"))) or r)
        dmid = (dm or {}).get("VersionId")
        rep.expect_err("get after delete marker -> NoSuchKey", lambda: s3.get_object(Bucket=BV, Key="doc.txt"), "NoSuchKey", 404)
        rep.expect_err("head after delete marker -> 404", lambda: s3.head_object(Bucket=BV, Key="doc.txt"), "404", 404)
        rep.check("list_objects_v2 hides key behind delete marker", lambda: s3.list_objects_v2(Bucket=BV),
                  lambda r: r["KeyCount"] == 0 or [o["Key"] for o in r["Contents"]])
        rep.check("list_objects v1 hides key behind delete marker", lambda: s3.list_objects(Bucket=BV),
                  lambda r: not r.get("Contents") or [o["Key"] for o in r["Contents"]])
        rep.check("list_object_versions shows DeleteMarker", lambda: s3.list_object_versions(Bucket=BV),
                  lambda r: ([d["VersionId"] for d in r.get("DeleteMarkers", [])] == [dmid] and r["DeleteMarkers"][0]["IsLatest"] is True
                             and len(r["Versions"]) == 2) or (r.get("DeleteMarkers"), len(r.get("Versions", []))))
        rep.check("get v1 still works by VersionId", lambda: s3.get_object(Bucket=BV, Key="doc.txt", VersionId=vid1), lambda r: body(r) == b"version one" or "mismatch")
        rep.expect_err("copy_object from the delete marker version -> InvalidRequest",
                       lambda: s3.copy_object(Bucket=BV, Key="from-marker.txt", CopySource={"Bucket": BV, "Key": "doc.txt", "VersionId": dmid}), "InvalidRequest", 400)
        rep.check("delete the delete marker restores object (echoes x-amz-delete-marker)",
                  lambda: s3.delete_object(Bucket=BV, Key="doc.txt", VersionId=dmid),
                  lambda r: (r.get("DeleteMarker") is True and r.get("VersionId") == dmid) or r)
        rep.check("get after marker removal -> v2", lambda: s3.get_object(Bucket=BV, Key="doc.txt"), lambda r: body(r) == b"version two" or "mismatch")
        rep.check("delete specific version v2 (echoes x-amz-version-id)", lambda: s3.delete_object(Bucket=BV, Key="doc.txt", VersionId=vid2),
                  lambda r: (r.get("VersionId") == vid2 and not r.get("DeleteMarker")) or r)
        rep.check("get after v2 deleted -> v1 is latest", lambda: s3.get_object(Bucket=BV, Key="doc.txt"), lambda r: body(r) == b"version one" or "mismatch")
        rep.check("copy_object with VersionId source",
                  lambda: s3.copy_object(Bucket=BV, Key="doc-from-v1.txt", CopySource={"Bucket": BV, "Key": "doc.txt", "VersionId": vid1}),
                  lambda r: (r.get("CopySourceVersionId") == vid1 and bool(r.get("VersionId"))) or r)

        def mpu_versioned():
            uid = s3.create_multipart_upload(Bucket=BV, Key="mpu.bin")["UploadId"]
            etag = s3.upload_part(Bucket=BV, Key="mpu.bin", UploadId=uid, PartNumber=1, Body=b"m" * 1024)["ETag"]
            return s3.complete_multipart_upload(Bucket=BV, Key="mpu.bin", UploadId=uid, MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": etag}]})

        rep.check("multipart on versioned bucket returns VersionId", mpu_versioned, lambda r: bool(r.get("VersionId")) or r)
        rep.check("put_bucket_versioning Suspended", lambda: s3.put_bucket_versioning(Bucket=BV, VersioningConfiguration={"Status": "Suspended"}))
        rep.check("put on suspended -> VersionId null", lambda: s3.put_object(Bucket=BV, Key="doc.txt", Body=b"suspended"),
                  lambda r: r.get("VersionId") in (None, "null") or r.get("VersionId"))
        rep.check("list_object_versions after suspend keeps real versions", lambda: s3.list_object_versions(Bucket=BV, Prefix="doc.txt"),
                  lambda r: [(v["VersionId"], v["IsLatest"]) for v in r["Versions"]] == [("null", True), (vid1, False)]
                  or [(v["VersionId"], v["IsLatest"]) for v in r["Versions"]])
        rep.check("second put on suspended replaces only the null version", lambda: s3.put_object(Bucket=BV, Key="doc.txt", Body=b"suspended again"),
                  lambda r: [v["VersionId"] for v in s3.list_object_versions(Bucket=BV, Prefix="doc.txt")["Versions"]] == ["null", vid1]
                  or [v["VersionId"] for v in s3.list_object_versions(Bucket=BV, Prefix="doc.txt")["Versions"]])
        rep.check("delete on suspended inserts a null delete marker", lambda: s3.delete_object(Bucket=BV, Key="doc.txt"),
                  lambda r: (r.get("DeleteMarker") is True and r.get("VersionId") == "null") or r)
        rep.check("suspended delete kept v1 and replaced the null version", lambda: s3.list_object_versions(Bucket=BV, Prefix="doc.txt"),
                  lambda r: ([v["VersionId"] for v in r["Versions"]] == [vid1] and [d["VersionId"] for d in r.get("DeleteMarkers", [])] == ["null"])
                  or ([v["VersionId"] for v in r["Versions"]], r.get("DeleteMarkers")))
        rep.expect_err("delete_bucket non-empty versioned -> BucketNotEmpty", lambda: s3.delete_bucket(Bucket=BV), "BucketNotEmpty", 409)

    def auth_section(self):
        s3, B, rep = self.s3, self.B, self.rep
        rep.section("auth / errors")
        rep.expect_err("wrong secret -> SignatureDoesNotMatch", lambda: self.client(sk="wrong" + self.sk[5:]).list_buckets(), "SignatureDoesNotMatch", 403)
        rep.expect_err("unknown access key -> InvalidAccessKeyId", lambda: self.client(ak="AKIAUNKNOWN0000000000").list_buckets(), "InvalidAccessKeyId", 403)
        anon = boto3.client("s3", endpoint_url=self.endpoint, region_name=self.region,
                            verify=False if self.args.insecure else (self.args.ca_bundle or None),
                            config=Config(signature_version=botocore.UNSIGNED, s3={"addressing_style": "path"}))
        rep.expect_err("anonymous list_buckets -> AccessDenied", lambda: anon.list_buckets(), "AccessDenied", 403)
        rep.expect_err("anonymous get object -> AccessDenied", lambda: anon.get_object(Bucket=B, Key="docs/readme.txt"), "AccessDenied", 403)
        rep.expect_err("anonymous put object -> AccessDenied", lambda: anon.put_object(Bucket=B, Key="anon.txt", Body=b"x"), "AccessDenied", 403)
        if self.args.skip_sigv2:
            rep.rec("SKIP", "SigV2 checks", "--skip-sigv2")
        else:
            v2c = self.client(sig="s3")
            rep.check("SigV2 list_buckets", lambda: v2c.list_buckets(), lambda r: B in [b["Name"] for b in r["Buckets"]])
            rep.check("SigV2 put_object", lambda: v2c.put_object(Bucket=B, Key="sigv2.txt", Body=b"signed with v2", ContentType="text/plain"))
            rep.check("SigV2 get_object", lambda: v2c.get_object(Bucket=B, Key="sigv2.txt"), lambda r: body(r) == b"signed with v2" or "mismatch")
            rep.check("SigV2 special key", lambda: v2c.get_object(Bucket=B, Key="with space.txt"), lambda r: body(r) == b"key:with space.txt" or "mismatch")
            rep.check("SigV2 presigned GET", lambda: fetch(v2c.generate_presigned_url("get_object", Params={"Bucket": B, "Key": "sigv2.txt"}, ExpiresIn=300)),
                      lambda r: (r[0] == 200 and r[1] == b"signed with v2") or (r[0], r[1][:200]))
            rep.check("SigV2 delete_object", lambda: v2c.delete_object(Bucket=B, Key="sigv2.txt"))
        rep.check("200 sequential HEADs on one connection", lambda: [s3.head_object(Bucket=B, Key="docs/readme.txt") for _ in range(200)] and True)

    def concurrency_section(self):
        s3, B2, rep = self.s3, self.B2, self.rep
        rep.section("concurrency")

        def concurrent_puts():
            with concurrent.futures.ThreadPoolExecutor(16) as ex:
                list(ex.map(lambda i: s3.put_object(Bucket=B2, Key=f"conc/{i}.txt", Body=f"c{i}".encode() * 100), range(64)))
            return s3.list_objects_v2(Bucket=B2, Prefix="conc/")["KeyCount"]

        def concurrent_same_key():
            with concurrent.futures.ThreadPoolExecutor(8) as ex:
                list(ex.map(lambda i: s3.put_object(Bucket=B2, Key="conc/same.txt", Body=f"writer-{i}".encode()), range(32)))
            return body(s3.get_object(Bucket=B2, Key="conc/same.txt"))

        rep.check("64 concurrent puts (16 threads)", concurrent_puts, lambda r: r == 64 or r)
        rep.check("32 concurrent puts same key -> consistent final body", concurrent_same_key, lambda r: r.startswith(b"writer-") or r)

    def cleanup_section(self):
        s3, rep = self.s3, self.rep
        rep.section("cleanup")
        rep.expect_err("delete_bucket non-empty -> BucketNotEmpty", lambda: s3.delete_bucket(Bucket=self.B), "BucketNotEmpty", 409)
        for b in self.buckets:
            rep.check(f"empty {b} via list_object_versions+delete_objects", lambda b=b: self.empty_bucket(b), lambda r: r == 0 or r)
            rep.check(f"delete_bucket {b}", lambda b=b: s3.delete_bucket(Bucket=b))
            rep.expect_err(f"head deleted bucket {b}", lambda b=b: s3.head_bucket(Bucket=b), "404", 404)
        rep.expect_err("delete_bucket twice -> NoSuchBucket", lambda: s3.delete_bucket(Bucket=self.B), "NoSuchBucket", 404)

    # -- plumbing ---------------------------------------------------------

    def empty_bucket(self, b: str) -> int:
        """Empties a bucket the way `aws s3 rb --force` does: every version and delete marker."""
        s3 = self.s3
        for page in s3.get_paginator("list_object_versions").paginate(Bucket=b):
            objs = [{"Key": v["Key"], "VersionId": v["VersionId"]} for v in page.get("Versions", []) + page.get("DeleteMarkers", [])]
            if objs:
                s3.delete_objects(Bucket=b, Delete={"Objects": objs, "Quiet": True})
        for upload in s3.list_multipart_uploads(Bucket=b).get("Uploads", []):
            s3.abort_multipart_upload(Bucket=b, Key=upload["Key"], UploadId=upload["UploadId"])
        return s3.list_objects_v2(Bucket=b)["KeyCount"]

    def force_cleanup(self) -> None:
        """Best-effort removal of the smoke buckets after a crash or with --keep unset."""
        for b in self.buckets:
            try:
                self.empty_bucket(b)
                self.s3.delete_bucket(Bucket=b)
            except ClientError:
                pass

    SECTIONS = ["buckets", "objects", "listing", "copy", "tagging", "delete", "multipart",
                "checksums", "presigned", "versioning", "auth", "concurrency", "cleanup"]

    def run(self) -> int:
        only = set(self.args.only.split(",")) if self.args.only else None
        print(f"Leonardo smoke test against {self.endpoint} (region {self.region}, key {self.ak})")
        try:
            for name in self.SECTIONS:
                # bucket creation and the final cleanup always run; objects are needed by most sections
                if only and name not in only and name not in ("buckets", "objects", "cleanup"):
                    continue
                if name == "cleanup" and self.args.keep:
                    print(f"\n=== keeping buckets: {', '.join(self.buckets)}")
                    continue
                getattr(self, f"{name}_section")()
        except KeyboardInterrupt:
            print("\ninterrupted")
        finally:
            if not self.args.keep:
                self.force_cleanup()
            self.tmpdir.cleanup()
        return self.rep.summary()


def parse_args(argv=None) -> argparse.Namespace:
    env = os.environ.get
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--endpoint", default=env("LEONARDO_ENDPOINT", "http://127.0.0.1:9000"),
                   help="server URL (env LEONARDO_ENDPOINT, default http://127.0.0.1:9000)")
    p.add_argument("--access-key", default=env("LEONARDO_ACCESS_KEY"), help="env LEONARDO_ACCESS_KEY")
    p.add_argument("--secret-key", default=env("LEONARDO_SECRET_KEY"), help="env LEONARDO_SECRET_KEY")
    p.add_argument("--region", default=env("LEONARDO_REGION", "us-east-1"),
                   help="must match leonardo.server.region (env LEONARDO_REGION, default us-east-1)")
    p.add_argument("--prefix", default="smoke", help="bucket name prefix (default smoke); a random suffix is always added")
    p.add_argument("--part-size-mib", type=int, default=5,
                   help="multipart part size in MiB; must be at least leonardo.limits.min_part_size (default 5)")
    p.add_argument("--only", help="comma-separated sections to run: " + ",".join(Smoke.SECTIONS))
    p.add_argument("--skip-sigv2", action="store_true", help="skip SigV2 checks (server runs with allow_sigv2: false)")
    p.add_argument("--keep", action="store_true", help="leave the smoke buckets in place for inspection")
    p.add_argument("--insecure", action="store_true", help="do not verify TLS certificates")
    p.add_argument("--ca-bundle", help="CA bundle for a TLS endpoint with a private certificate")
    p.add_argument("-v", "--verbose", action="store_true", help="print tracebacks for unexpected exceptions")
    args = p.parse_args(argv)
    if not args.access_key or not args.secret_key:
        p.error("access key and secret key are required (LEONARDO_ACCESS_KEY / LEONARDO_SECRET_KEY)")
    return args


if __name__ == "__main__":
    sys.exit(Smoke(parse_args()).run())
