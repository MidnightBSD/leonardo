/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

plugins {
    java
}

// Versions shared across all modules. Bump here, propagates everywhere.
val micronautVersion by extra("4.8.3")
val nettyVersion by extra("4.1.115.Final")
val jacksonVersion by extra("2.18.2")
val snakeYamlVersion by extra("2.4")
val slf4jVersion by extra("2.0.17")
val logbackVersion by extra("1.5.17")
val junitVersion by extra("5.11.4")
val assertjVersion by extra("3.27.3")
val jqwikVersion by extra("1.9.1")
val mockitoVersion by extra("5.14.2")
val awsSdkVersion by extra("2.29.43")

allprojects {
    group = "org.midnightbsd.leonardo"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
        // Virtual threads are used in production; exercise them in tests too.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }

    dependencies {
        "implementation"("org.slf4j:slf4j-api:${rootProject.extra["slf4jVersion"]}")

        "testImplementation"(platform("org.junit:junit-bom:${rootProject.extra["junitVersion"]}"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.junit.jupiter:junit-jupiter-params")
        "testImplementation"("org.assertj:assertj-core:${rootProject.extra["assertjVersion"]}")
        "testImplementation"("org.mockito:mockito-core:${rootProject.extra["mockitoVersion"]}")
        "testImplementation"("org.mockito:mockito-junit-jupiter:${rootProject.extra["mockitoVersion"]}")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testRuntimeOnly"("ch.qos.logback:logback-classic:${rootProject.extra["logbackVersion"]}")
    }
}
