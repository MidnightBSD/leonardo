/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    `java-library`
}

val awsSdkVersion = rootProject.extra["awsSdkVersion"] as String

dependencies {
    testAnnotationProcessor(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")

    testImplementation(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.micronaut:micronaut-http-server-netty")
    testImplementation("io.micronaut.serde:micronaut-serde-jackson")
    testImplementation("jakarta.annotation:jakarta.annotation-api")

    // Pull in the full application context (all controllers, services, storage, auth, admin)
    testImplementation(project(":leonardo-app"))
    testImplementation(project(":leonardo-api"))
    testImplementation(project(":leonardo-core"))

    // AWS SDK v2 — S3 client for driving conformance assertions
    testImplementation(platform("software.amazon.awssdk:bom:${awsSdkVersion}"))
    testImplementation("software.amazon.awssdk:s3")
    testImplementation("software.amazon.awssdk:url-connection-client")

    testRuntimeOnly("org.yaml:snakeyaml:${rootProject.extra["snakeYamlVersion"]}")
    testRuntimeOnly("ch.qos.logback:logback-classic:${rootProject.extra["logbackVersion"]}")
}

// Conformance tests start a real HTTP server and are slower than unit tests;
// give them a generous per-test timeout via JUnit 5's @Timeout support.
tasks.named<Test>("test") {
    // Allow long per-class startup (Micronaut context init)
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
}
