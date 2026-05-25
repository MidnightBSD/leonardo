/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    `java-library`
}

dependencies {
    api(project(":leonardo-storage"))
    api(project(":leonardo-auth"))
    api(project(":leonardo-yaml"))
    api("com.fasterxml.jackson.core:jackson-databind:${rootProject.extra["jacksonVersion"]}")
}
