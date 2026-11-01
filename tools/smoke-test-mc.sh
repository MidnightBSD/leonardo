#!/bin/sh
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
# Smoke test for a running Leonardo server using the MinIO client (mc).
#
# Drives the workflows an operator reaches for first: make a bucket, copy
# files in and out, list, stat, mirror a directory, tag objects, turn on
# versioning, delete, and finally remove the bucket with every version in it.
# mc talks to the server the way MinIO does, so this catches compatibility
# problems that boto3 does not (its own request shapes, `rm --versions`
# relying on <VersionId>null</VersionId>, trailing-slash "folder" objects).
#
# Configuration is taken from the environment:
#   LEONARDO_ENDPOINT    default http://127.0.0.1:9000
#   LEONARDO_ACCESS_KEY  required
#   LEONARDO_SECRET_KEY  required
#   MC                   path to the mc binary (default: mc on PATH)
#   MC_INSECURE=1        pass --insecure to every mc call (private TLS certs)
#
# The script uses a private MC_CONFIG_DIR, so it never touches ~/.mc. The
# exit status is non-zero when any step fails.

set -u

ENDPOINT=${LEONARDO_ENDPOINT:-http://127.0.0.1:9000}
MC=${MC:-mc}
ALIAS=leonardo-smoke
RUN_ID=$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
BUCKET=mc-smoke-$RUN_ID
BUCKET_V=mc-smoke-ver-$RUN_ID

if [ -z "${LEONARDO_ACCESS_KEY:-}" ] || [ -z "${LEONARDO_SECRET_KEY:-}" ]; then
    echo "LEONARDO_ACCESS_KEY and LEONARDO_SECRET_KEY must be set" >&2
    exit 2
fi
if ! command -v "$MC" >/dev/null 2>&1; then
    echo "mc not found; install the MinIO client or set MC=/path/to/mc" >&2
    exit 2
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/leonardo-mc-smoke.XXXXXX")
export MC_CONFIG_DIR="$WORK/mc"
mkdir -p "$MC_CONFIG_DIR"
INSECURE=""
[ "${MC_INSECURE:-0}" = 1 ] && INSECURE="--insecure"

PASS=0
FAIL=0
LOG="$WORK/last.log"

# run NAME CMD... : PASS when the command exits 0
run() {
    name=$1; shift
    if "$@" >"$LOG" 2>&1; then
        PASS=$((PASS + 1)); echo "PASS $name"
    else
        FAIL=$((FAIL + 1)); echo "FAIL $name"; sed 's/^/     /' "$LOG"
    fi
}

# run_fail NAME CMD... : PASS when the command exits non-zero
run_fail() {
    name=$1; shift
    if "$@" >"$LOG" 2>&1; then
        FAIL=$((FAIL + 1)); echo "FAIL $name (expected an error)"; sed 's/^/     /' "$LOG"
    else
        PASS=$((PASS + 1)); echo "PASS $name"
    fi
}

# expect NAME EXPECTED CMD... : PASS when the command's stdout equals EXPECTED
expect() {
    name=$1; want=$2; shift 2
    got=$("$@" 2>"$LOG")
    if [ "$got" = "$want" ]; then
        PASS=$((PASS + 1)); echo "PASS $name"
    else
        FAIL=$((FAIL + 1)); echo "FAIL $name"; echo "     expected: $want"; echo "     got:      $got"; sed 's/^/     /' "$LOG"
    fi
}

# contains NAME NEEDLE CMD... : PASS when NEEDLE occurs in the command's stdout
contains() {
    name=$1; needle=$2; shift 2
    out=$("$@" 2>"$LOG")
    if printf '%s\n' "$out" | grep -qF -- "$needle"; then
        PASS=$((PASS + 1)); echo "PASS $name"
    else
        FAIL=$((FAIL + 1)); echo "FAIL $name"; echo "     missing: $needle"; printf '%s\n' "$out" | sed 's/^/     /'; sed 's/^/     /' "$LOG"
    fi
}

mc() { command "$MC" $INSECURE "$@"; }

cleanup() {
    mc rb --force "$ALIAS/$BUCKET" >/dev/null 2>&1
    mc rb --force "$ALIAS/$BUCKET_V" >/dev/null 2>&1
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

echo "Leonardo mc smoke test against $ENDPOINT (bucket $BUCKET)"

# --- fixtures ---------------------------------------------------------------
SRC="$WORK/src"; DL="$WORK/dl"; MIRROR="$WORK/mirror"
mkdir -p "$SRC/sub/deeper" "$DL" "$MIRROR"
printf 'hello from mc\n' >"$SRC/hello.txt"
printf 'nested\n' >"$SRC/sub/nested.txt"
printf 'deeper\n' >"$SRC/sub/deeper/leaf.txt"
: >"$SRC/empty.bin"
dd if=/dev/urandom of="$SRC/large.bin" bs=1048576 count=12 2>/dev/null
cksum_of() { cksum "$1" | awk '{print $1, $2}'; }

# --- alias and bucket -------------------------------------------------------
run "alias set"            mc alias set "$ALIAS" "$ENDPOINT" "$LEONARDO_ACCESS_KEY" "$LEONARDO_SECRET_KEY" --api S3v4 --path on
run "mb"                   mc mb "$ALIAS/$BUCKET"
run_fail "mb duplicate fails" mc mb "$ALIAS/$BUCKET"
contains "ls shows bucket" "$BUCKET/" mc ls "$ALIAS"

# --- objects ----------------------------------------------------------------
run "cp file"              mc cp "$SRC/hello.txt" "$ALIAS/$BUCKET/hello.txt"
expect "cat matches"       "hello from mc" mc cat "$ALIAS/$BUCKET/hello.txt"
run "cp empty file"        mc cp "$SRC/empty.bin" "$ALIAS/$BUCKET/empty.bin"
run "cp 12 MiB (multipart)" mc cp "$SRC/large.bin" "$ALIAS/$BUCKET/large.bin"
run "cp back 12 MiB"       mc cp "$ALIAS/$BUCKET/large.bin" "$DL/large.bin"
expect "12 MiB round trip" "$(cksum_of "$SRC/large.bin")" cksum_of "$DL/large.bin"
run "cp with attr"         mc cp --attr "Content-Type=text/x-custom;x-amz-meta-origin=mc" "$SRC/hello.txt" "$ALIAS/$BUCKET/attr.txt"
contains "stat shows content type" "text/x-custom" mc stat "$ALIAS/$BUCKET/attr.txt"
contains "stat shows user metadata" "mc" mc stat "$ALIAS/$BUCKET/attr.txt"
run "cp recursive"         mc cp --recursive "$SRC/sub" "$ALIAS/$BUCKET/tree/"
contains "ls recursive sees nested" "tree/sub/deeper/leaf.txt" mc ls --recursive "$ALIAS/$BUCKET"
contains "ls prefix shows folder" "sub/" mc ls "$ALIAS/$BUCKET/tree/"
run "server-side cp"       mc cp "$ALIAS/$BUCKET/hello.txt" "$ALIAS/$BUCKET/copies/hello-copy.txt"
expect "server-side copy content" "hello from mc" mc cat "$ALIAS/$BUCKET/copies/hello-copy.txt"
run "mv"                   mc mv "$ALIAS/$BUCKET/copies/hello-copy.txt" "$ALIAS/$BUCKET/copies/moved.txt"
run_fail "moved source gone" mc stat "$ALIAS/$BUCKET/copies/hello-copy.txt"
run "find by name"         mc find "$ALIAS/$BUCKET" --name "*.txt"
run "mirror to local"      mc mirror "$ALIAS/$BUCKET/tree" "$MIRROR"
expect "mirrored leaf"     "deeper" cat "$MIRROR/sub/deeper/leaf.txt"
run "mirror local to bucket" mc mirror "$SRC/sub" "$ALIAS/$BUCKET/mirrored/"
contains "mirror arrived"  "deeper/leaf.txt" mc ls --recursive "$ALIAS/$BUCKET/mirrored/"
run "mirror again is idempotent" mc mirror "$SRC/sub" "$ALIAS/$BUCKET/mirrored/"
run "share download (presigned)" mc share download "$ALIAS/$BUCKET/hello.txt"

# --- tags -------------------------------------------------------------------
run "tag set"              mc tag set "$ALIAS/$BUCKET/hello.txt" "env=test&team=ops"
contains "tag list"        "team" mc tag list "$ALIAS/$BUCKET/hello.txt"
run "tag remove"           mc tag remove "$ALIAS/$BUCKET/hello.txt"
run "bucket tag set"       mc tag set "$ALIAS/$BUCKET" "purpose=smoke"
contains "bucket tag list" "purpose" mc tag list "$ALIAS/$BUCKET"
run "bucket tag remove"    mc tag remove "$ALIAS/$BUCKET"

# --- delete -----------------------------------------------------------------
run "rm one"               mc rm "$ALIAS/$BUCKET/empty.bin"
run_fail "removed object gone" mc stat "$ALIAS/$BUCKET/empty.bin"
run "rm recursive prefix"  mc rm --recursive --force "$ALIAS/$BUCKET/tree/"
expect "prefix is empty"   "" mc ls --recursive "$ALIAS/$BUCKET/tree/"

# --- versioning -------------------------------------------------------------
run "mb versioned"         mc mb "$ALIAS/$BUCKET_V"
run "version enable"       mc version enable "$ALIAS/$BUCKET_V"
contains "version info"    "is enabled" mc version info "$ALIAS/$BUCKET_V"
run "put v1"               mc cp "$SRC/hello.txt" "$ALIAS/$BUCKET_V/doc.txt"
run "put v2"               mc cp "$SRC/sub/nested.txt" "$ALIAS/$BUCKET_V/doc.txt"
expect "latest is v2"      "nested" mc cat "$ALIAS/$BUCKET_V/doc.txt"
expect "two versions listed" "2" sh -c "'$MC' $INSECURE ls --versions '$ALIAS/$BUCKET_V/doc.txt' | wc -l | tr -d ' '"
run "rm creates delete marker" mc rm "$ALIAS/$BUCKET_V/doc.txt"
expect "ls hides deleted key" "" mc ls "$ALIAS/$BUCKET_V"
contains "ls --versions shows marker" "DEL" mc ls --versions "$ALIAS/$BUCKET_V"
run "undo (remove delete marker)" mc undo "$ALIAS/$BUCKET_V/doc.txt"
expect "restored after undo" "nested" mc cat "$ALIAS/$BUCKET_V/doc.txt"
run "rm all versions"      mc rm --versions --recursive --force "$ALIAS/$BUCKET_V"
expect "no versions left"  "" mc ls --versions "$ALIAS/$BUCKET_V"

# --- bucket removal ---------------------------------------------------------
run_fail "rb non-empty fails" mc rb "$ALIAS/$BUCKET"
run "rb --force (unversioned, uses VersionId null)" mc rb --force "$ALIAS/$BUCKET"
run "rb --force versioned"  mc rb --force "$ALIAS/$BUCKET_V"
run_fail "bucket gone"      mc ls "$ALIAS/$BUCKET"

echo
echo "SUMMARY: PASS $PASS  FAIL $FAIL"
[ "$FAIL" -eq 0 ]
