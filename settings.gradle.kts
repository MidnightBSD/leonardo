/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

rootProject.name = "leonardo"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "leonardo-app",
    "leonardo-api",
    "leonardo-core",
    "leonardo-storage",
    "leonardo-auth",
    "leonardo-yaml",
    "leonardo-xml",
    "leonardo-admin",
    "leonardo-cli",
    "leonardo-conformance"
)
