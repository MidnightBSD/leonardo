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
    api("io.micronaut:micronaut-inject")
    api("jakarta.inject:jakarta.inject-api")
    api(project(":leonardo-yaml"))

    testAnnotationProcessor(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testImplementation(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.micronaut:micronaut-http-server-netty")
    testRuntimeOnly("org.yaml:snakeyaml:${rootProject.extra["snakeYamlVersion"]}")
}
