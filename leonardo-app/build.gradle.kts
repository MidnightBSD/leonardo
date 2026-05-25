/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    `java-library`
    application
}

dependencies {
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    annotationProcessor("io.micronaut:micronaut-inject-java")

    implementation(platform("io.micronaut.platform:micronaut-platform:${rootProject.extra["micronautVersion"]}"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("ch.qos.logback:logback-classic:${rootProject.extra["logbackVersion"]}")
    implementation("jakarta.annotation:jakarta.annotation-api")

    implementation(project(":leonardo-api"))
    implementation(project(":leonardo-admin"))
    implementation(project(":leonardo-core"))

    runtimeOnly("org.yaml:snakeyaml:${rootProject.extra["snakeYamlVersion"]}")
}

application {
    mainClass.set("org.midnightbsd.leonardo.app.LeonardoApplication")
    applicationName = "leonardo"
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseG1GC",
        "-XX:MaxRAMPercentage=75.0",
        "--enable-native-access=ALL-UNNAMED"
    )
}

tasks.named<JavaExec>("run") {
    systemProperty("micronaut.environments", "dev")
}
