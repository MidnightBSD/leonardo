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

    api(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    api("io.micronaut:micronaut-http-server-netty")
    api("io.micronaut:micronaut-management")
    api(project(":leonardo-core"))
}
