/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.core;

/**
 * Signals an S3-protocol error that should be returned to the client as an
 * XML {@code <Error>} response with the given HTTP status code.
 *
 * <p>Thrown by services and caught by controllers, which translate it into
 * the appropriate HTTP response.
 */
public final class S3Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Common S3 error codes (not exhaustive — add as needed).
    public static final String NO_SUCH_BUCKET        = "NoSuchBucket";
    public static final String NO_SUCH_KEY           = "NoSuchKey";
    public static final String BUCKET_ALREADY_EXISTS = "BucketAlreadyOwnedByYou";
    public static final String BUCKET_NOT_EMPTY      = "BucketNotEmpty";
    public static final String INVALID_BUCKET_NAME   = "InvalidBucketName";
    public static final String INVALID_ARGUMENT      = "InvalidArgument";
    public static final String OBJECT_ID_CONFLICT    = "ObjectIdConflict";
    public static final String NOT_IMPLEMENTED       = "NotImplemented";
    public static final String INVALID_LOCATION      = "InvalidLocationConstraint";

    private final String code;
    private final int httpStatus;

    public S3Exception(final String code, final String message, final int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }

    // -------------------------------------------------------------------------
    // Factory helpers for the most common cases
    // -------------------------------------------------------------------------

    public static S3Exception noSuchBucket(final String bucket) {
        return new S3Exception(NO_SUCH_BUCKET,
                "The specified bucket does not exist: " + bucket, 404);
    }

    public static S3Exception noSuchKey(final String key) {
        return new S3Exception(NO_SUCH_KEY,
                "The specified key does not exist: " + key, 404);
    }

    public static S3Exception bucketAlreadyExists(final String bucket) {
        return new S3Exception(BUCKET_ALREADY_EXISTS,
                "Your previous request to create the named bucket succeeded and you already own it: "
                        + bucket, 409);
    }

    public static S3Exception bucketNotEmpty(final String bucket) {
        return new S3Exception(BUCKET_NOT_EMPTY,
                "The bucket you tried to delete is not empty: " + bucket, 409);
    }

    public static S3Exception invalidBucketName(final String bucket) {
        return new S3Exception(INVALID_BUCKET_NAME,
                "The specified bucket is not valid: " + bucket, 400);
    }
}
