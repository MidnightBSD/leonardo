/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    `java-library`
}

dependencies {
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")

    api(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    api("io.micronaut:micronaut-http-server-netty")
    api("io.micronaut:micronaut-inject")
    api("io.micronaut:micronaut-runtime")
    api("io.micronaut.validation:micronaut-validation")
    api("jakarta.annotation:jakarta.annotation-api")
    api(project(":leonardo-auth"))
    api(project(":leonardo-core"))
    api(project(":leonardo-xml"))

    testAnnotationProcessor(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testImplementation(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.micronaut:micronaut-http-client")
}
