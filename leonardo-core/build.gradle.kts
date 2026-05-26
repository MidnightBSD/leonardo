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
    api("jakarta.inject:jakarta.inject-api:2.0.1")
    api(project(":leonardo-storage"))
    api(project(":leonardo-auth"))
    api(project(":leonardo-yaml"))
    api("com.fasterxml.jackson.core:jackson-databind:${rootProject.extra["jacksonVersion"]}")
}
