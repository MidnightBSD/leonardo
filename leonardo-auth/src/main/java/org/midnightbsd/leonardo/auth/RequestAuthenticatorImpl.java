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
package org.midnightbsd.leonardo.auth;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.auth.sigv2.SigV2Verifier;
import org.midnightbsd.leonardo.auth.sigv4.SigV4Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Authorization header, builds the string-to-sign, and delegates
 * to {@link SigV4Verifier} or {@link SigV2Verifier} for the HMAC check.
 *
 * <h2>SigV4 Authorization header format</h2>
 * <pre>
 * AWS4-HMAC-SHA256 Credential=AKID/YYYYMMDD/region/service/aws4_request,
 *     SignedHeaders=host;x-amz-content-sha256;x-amz-date,
 *     Signature=hexsig
 * </pre>
 *
 * <h2>SigV2 Authorization header format</h2>
 * <pre>AWS AWSAccessKeyId:Base64Signature</pre>
 *
 * <p>Pre-signed URL verification is deferred to M2.
 */
@Singleton
public final class RequestAuthenticatorImpl implements RequestAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(RequestAuthenticatorImpl.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    // AWS4-HMAC-SHA256 Credential=AKID/DATE/REGION/SERVICE/aws4_request,
    //   SignedHeaders=h1;h2, Signature=hexsig
    private static final Pattern SIGV4 = Pattern.compile(
            "AWS4-HMAC-SHA256 Credential=([^/]+)/([^/]+)/([^/]+)/([^/]+)/aws4_request"
                    + ",\\s*SignedHeaders=([^,]+),\\s*Signature=([0-9a-fA-F]+)");

    // AWS AccessKeyId:Base64Signature
    private static final Pattern SIGV2 = Pattern.compile("AWS ([^:]+):(.+)");

    // Parses X-Amz-Date format: 20260527T120000Z
    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    // S3 sub-resources that are included in the SigV2 CanonicalizedResource
    private static final Set<String> SIGV2_SUB_RESOURCES = Set.of(
            "acl", "cors", "delete", "lifecycle", "location", "logging",
            "notification", "partNumber", "policy", "requestPayment", "restore",
            "tagging", "torrent", "uploadId", "uploads", "versionId",
            "versioning", "versions", "website");

    private final SigV4Verifier sigV4Verifier;
    private final SigV2Verifier sigV2Verifier;
    private final boolean allowAnonymousRead;
    private final boolean allowSigV2;

    public RequestAuthenticatorImpl(
            final SigV4Verifier sigV4Verifier,
            final SigV2Verifier sigV2Verifier,
            @Value("${leonardo.auth.allow_anonymous_read:false}") final boolean allowAnonymousRead,
            @Value("${leonardo.auth.allow_sigv2:true}") final boolean allowSigV2) {
        this.sigV4Verifier = sigV4Verifier;
        this.sigV2Verifier = sigV2Verifier;
        this.allowAnonymousRead = allowAnonymousRead;
        this.allowSigV2 = allowSigV2;
    }

    @Override
    public Optional<String> authenticate(
            final String method, final URI uri, final Map<String, List<String>> headers) {

        final String auth = firstHeader(headers, "authorization");

        if (auth == null || auth.isBlank()) {
            // Check for SigV4 presigned URL (X-Amz-Signature in query string)
            final Map<String, String> queryParams = parseQueryParams(uri.getRawQuery());
            if (queryParams.containsKey("X-Amz-Signature")) {
                return authenticatePresigned(method, uri, headers, queryParams);
            }
            if (allowAnonymousRead && "GET".equalsIgnoreCase(method)) {
                return Optional.of("anonymous");
            }
            LOG.debug("Request missing Authorization header");
            return Optional.empty();
        }

        if (auth.startsWith("AWS4-HMAC-SHA256 ")) {
            return authenticateSigV4(method, uri, headers, auth);
        }
        if (auth.startsWith("AWS ")) {
            if (!allowSigV2) {
                LOG.debug("SigV2 request rejected: allow_sigv2 is false");
                return Optional.empty();
            }
            return authenticateSigV2(method, uri, headers, auth);
        }

        LOG.debug("Unrecognised Authorization scheme");
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // SigV4
    // -------------------------------------------------------------------------

    private Optional<String> authenticateSigV4(
            final String method, final URI uri,
            final Map<String, List<String>> headers, final String auth) {

        final Matcher m = SIGV4.matcher(auth);
        if (!m.find()) {
            LOG.debug("SigV4: malformed Authorization header");
            return Optional.empty();
        }

        final String accessKey     = m.group(1);
        final String date          = m.group(2);   // YYYYMMDD
        final String region        = m.group(3);
        final String service       = m.group(4);
        final String signedHeaders = m.group(5);   // semicolon-separated, lower-case
        final String signature     = m.group(6);

        final String timestamp = firstHeader(headers, "x-amz-date");
        if (timestamp == null) {
            LOG.debug("SigV4: missing X-Amz-Date header");
            return Optional.empty();
        }

        final String payloadHash = firstHeader(headers, "x-amz-content-sha256");
        if (payloadHash == null) {
            LOG.debug("SigV4: missing x-amz-content-sha256 header");
            return Optional.empty();
        }

        final String credentialScope = date + "/" + region + "/" + service + "/aws4_request";
        final String canonicalRequest = buildCanonicalRequest(
                method, uri, headers, signedHeaders, payloadHash);
        final String hashedCanonicalRequest = sha256Hex(canonicalRequest);
        final String stringToSign = "AWS4-HMAC-SHA256\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + hashedCanonicalRequest;

        final SigV4Verifier.SigV4Request req = new SigV4Verifier.SigV4Request(
                accessKey, date, region, service, signedHeaders, signature, stringToSign);
        return sigV4Verifier.verify(req);
    }

    private String buildCanonicalRequest(
            final String method, final URI uri,
            final Map<String, List<String>> headers,
            final String signedHeaders, final String payloadHash) {

        final String canonicalUri   = canonicalizeUri(uri.getRawPath());
        final String canonicalQuery = canonicalizeQueryString(uri.getRawQuery());
        final String canonicalHdrs  = buildCanonicalHeaders(headers, signedHeaders);

        return method.toUpperCase(Locale.ROOT) + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHdrs + "\n"   // already ends with \n
                + signedHeaders + "\n"
                + payloadHash;
    }

    private static String canonicalizeUri(final String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        final String[] segments = rawPath.split("/", -1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(uriEncode(segments[i]));
        }
        final String result = sb.toString();
        return result.isEmpty() ? "/" : result;
    }

    private static String canonicalizeQueryString(final String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        // Parse into sorted map (TreeMap sorts by key; tie-break by value)
        final Map<String, List<String>> params = new TreeMap<>();
        for (final String pair : rawQuery.split("&", -1)) {
            final int eq = pair.indexOf('=');
            final String key = eq < 0 ? pair : pair.substring(0, eq);
            final String val = eq < 0 ? "" : pair.substring(eq + 1);
            params.computeIfAbsent(uriEncode(key), k -> new ArrayList<>()).add(uriEncode(val));
        }
        final StringJoiner sj = new StringJoiner("&");
        params.forEach((k, vals) -> {
            Collections.sort(vals);
            vals.forEach(v -> sj.add(k + "=" + v));
        });
        return sj.toString();
    }

    private static String buildCanonicalHeaders(
            final Map<String, List<String>> headers, final String signedHeaders) {
        final StringBuilder sb = new StringBuilder();
        for (final String name : signedHeaders.split(";")) {
            final List<String> vals = headers.getOrDefault(name, List.of());
            final String joined = String.join(",", vals).trim().replaceAll("\\s+", " ");
            sb.append(name).append(':').append(joined).append('\n');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // SigV4 presigned URL
    // -------------------------------------------------------------------------

    private Optional<String> authenticatePresigned(
            final String method, final URI uri,
            final Map<String, List<String>> headers,
            final Map<String, String> queryParams) {

        final String algorithm   = queryParams.get("X-Amz-Algorithm");
        final String credential  = queryParams.get("X-Amz-Credential");
        final String dateParam   = queryParams.get("X-Amz-Date");
        final String expiresStr  = queryParams.get("X-Amz-Expires");
        final String signedHdrs  = queryParams.get("X-Amz-SignedHeaders");
        final String signature   = queryParams.get("X-Amz-Signature");

        if (!"AWS4-HMAC-SHA256".equals(algorithm)
                || credential == null || dateParam == null
                || expiresStr == null || signedHdrs == null || signature == null) {
            LOG.debug("Presigned URL: missing required params");
            return Optional.empty();
        }

        // Validate expiry
        try {
            final Instant signingTime = Instant.from(AMZ_DATE_FMT.parse(dateParam));
            final long expires = Long.parseLong(expiresStr);
            if (Instant.now().isAfter(signingTime.plusSeconds(expires))) {
                LOG.debug("Presigned URL: expired");
                return Optional.empty();
            }
        } catch (final DateTimeParseException | NumberFormatException ex) {
            LOG.debug("Presigned URL: bad date/expires: {}", ex.getMessage());
            return Optional.empty();
        }

        // Parse credential: AKID/date/region/service/aws4_request
        final String[] credParts = credential.split("/", -1);
        if (credParts.length < 5) {
            LOG.debug("Presigned URL: malformed credential '{}'", credential);
            return Optional.empty();
        }
        final String accessKey = credParts[0];
        final String date      = credParts[1];
        final String region    = credParts[2];
        final String service   = credParts[3];

        // Build canonical request for presigned URL:
        // payload hash is always UNSIGNED-PAYLOAD; query string excludes X-Amz-Signature
        final String canonicalUri   = canonicalizeUri(uri.getRawPath());
        final String canonicalQuery = canonicalizeRawQueryExcluding(
                uri.getRawQuery(), "X-Amz-Signature");
        final String canonicalHdrs  = buildCanonicalHeaders(headers, signedHdrs);

        final String canonicalRequest = method.toUpperCase(Locale.ROOT) + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHdrs + "\n"
                + signedHdrs + "\n"
                + "UNSIGNED-PAYLOAD";

        final String hashedCanonical = sha256Hex(canonicalRequest);
        final String credentialScope = date + "/" + region + "/" + service + "/aws4_request";
        final String stringToSign = "AWS4-HMAC-SHA256\n"
                + dateParam + "\n"
                + credentialScope + "\n"
                + hashedCanonical;

        final SigV4Verifier.SigV4Request req = new SigV4Verifier.SigV4Request(
                accessKey, date, region, service, signedHdrs, signature, stringToSign);
        final Optional<String> identity = sigV4Verifier.verify(req);
        if (identity.isEmpty()) {
            LOG.debug("Presigned URL: signature verification failed for accessKey={}", accessKey);
        }
        return identity;
    }

    /**
     * Builds a canonical query string from the raw (already-percent-encoded)
     * query string, excluding the named parameter. The raw values are used
     * as-is (no double-encoding) because the signer already encoded them.
     */
    private static String canonicalizeRawQueryExcluding(
            final String rawQuery, final String excludeKey) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        final Map<String, List<String>> params = new TreeMap<>();
        for (final String pair : rawQuery.split("&", -1)) {
            final int eq = pair.indexOf('=');
            final String key = eq < 0 ? pair : pair.substring(0, eq);
            final String val = eq < 0 ? "" : pair.substring(eq + 1);
            if (!key.equals(excludeKey)) {
                params.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
            }
        }
        final StringJoiner sj = new StringJoiner("&");
        params.forEach((k, vals) -> {
            Collections.sort(vals);
            vals.forEach(v -> sj.add(k + "=" + v));
        });
        return sj.toString();
    }

    /**
     * Parses raw query string into a single-value map (first occurrence wins).
     * Values are URL-decoded for easy comparison.
     */
    private static Map<String, String> parseQueryParams(final String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
        final Map<String, String> result = new LinkedHashMap<>();
        for (final String pair : rawQuery.split("&", -1)) {
            final int eq = pair.indexOf('=');
            final String key = eq < 0 ? pair : pair.substring(0, eq);
            final String val = eq < 0 ? "" : java.net.URLDecoder.decode(
                    pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.putIfAbsent(key, val);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // SigV2
    // -------------------------------------------------------------------------

    private Optional<String> authenticateSigV2(
            final String method, final URI uri,
            final Map<String, List<String>> headers, final String auth) {

        final Matcher m = SIGV2.matcher(auth);
        if (!m.find()) {
            LOG.debug("SigV2: malformed Authorization header");
            return Optional.empty();
        }
        final String accessKey = m.group(1);
        final String signature = m.group(2).trim();

        final String stringToSign = buildSigV2StringToSign(method, uri, headers);
        final SigV2Verifier.SigV2Request req =
                new SigV2Verifier.SigV2Request(accessKey, signature, stringToSign);

        final Optional<String> identity = sigV2Verifier.verify(req);
        if (identity.isPresent()) {
            AUDIT.info("SigV2 access: identity={} accessKey={} userAgent={}",
                    identity.get(), accessKey, firstHeader(headers, "user-agent"));
        }
        return identity;
    }

    private static String buildSigV2StringToSign(
            final String method, final URI uri, final Map<String, List<String>> headers) {

        final String contentMd5  = Objects.toString(firstHeader(headers, "content-md5"),  "");
        final String contentType = Objects.toString(firstHeader(headers, "content-type"), "");

        // If x-amz-date is present, use empty string for Date line (it moves to CanonicalizedAmzHeaders)
        final boolean hasAmzDate = headers.containsKey("x-amz-date");
        final String dateLine = hasAmzDate ? "" : Objects.toString(firstHeader(headers, "date"), "");

        final String amzHeaders  = buildCanonicalizedAmzHeaders(headers);
        final String resource    = buildCanonicalizedResource(uri);

        return method.toUpperCase(Locale.ROOT) + "\n"
                + contentMd5  + "\n"
                + contentType + "\n"
                + dateLine    + "\n"
                + amzHeaders
                + resource;
    }

    private static String buildCanonicalizedAmzHeaders(final Map<String, List<String>> headers) {
        final Map<String, String> amzHeaders = new TreeMap<>();
        headers.forEach((name, vals) -> {
            if (name.startsWith("x-amz-")) {
                amzHeaders.put(name, String.join(",", vals).trim().replaceAll("\\s+", " "));
            }
        });
        final StringBuilder sb = new StringBuilder();
        amzHeaders.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
        return sb.toString();
    }

    private static String buildCanonicalizedResource(final URI uri) {
        // /<path>
        final String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        final StringBuilder sb = new StringBuilder(path);

        // Append signed sub-resources, sorted alphabetically
        final String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            final Map<String, String> subResources = new TreeMap<>();
            for (final String pair : rawQuery.split("&", -1)) {
                final int eq = pair.indexOf('=');
                final String key = eq < 0 ? pair : pair.substring(0, eq);
                final String val = eq < 0 ? null : pair.substring(eq + 1);
                if (SIGV2_SUB_RESOURCES.contains(key)) {
                    subResources.put(key, val);
                }
            }
            if (!subResources.isEmpty()) {
                final StringJoiner sj = new StringJoiner("&", "?", "");
                subResources.forEach((k, v) -> sj.add(v == null ? k : k + "=" + v));
                sb.append(sj);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    static String uriEncode(final String value) {
        if (value == null || value.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder();
        for (final byte b : value.getBytes(StandardCharsets.UTF_8)) {
            final int i = b & 0xFF;
            if ((i >= 'A' && i <= 'Z') || (i >= 'a' && i <= 'z')
                    || (i >= '0' && i <= '9')
                    || i == '-' || i == '.' || i == '_' || i == '~') {
                sb.append((char) i);
            } else {
                sb.append(String.format("%%%02X", i));
            }
        }
        return sb.toString();
    }

    static String sha256Hex(final String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String firstHeader(final Map<String, List<String>> headers, final String name) {
        final List<String> vals = headers.get(name);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }
}
