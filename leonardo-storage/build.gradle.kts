/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    `java-library`
}

dependencies {
    api(project(":leonardo-yaml"))
    api("com.fasterxml.jackson.core:jackson-databind:${rootProject.extra["jacksonVersion"]}")

    testImplementation("net.jqwik:jqwik:${rootProject.extra["jqwikVersion"]}")
}

tasks.withType<Test>().configureEach {
    // jqwik uses its own test engine alongside JUnit Jupiter.
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
}
