/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    `java-library`
    application
}

dependencies {
    implementation("info.picocli:picocli:4.7.6")
    implementation("ch.qos.logback:logback-classic:${rootProject.extra["logbackVersion"]}")
    implementation(project(":leonardo-core"))
}

application {
    mainClass.set("org.midnightbsd.leonardo.cli.LeonardoAdminCli")
    applicationName = "leonardo-admin"
}
