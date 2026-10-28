/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 */

plugins {
    `java-library`
}

dependencies {
    api(platform("com.fasterxml.jackson:jackson-bom:${rootProject.extra["jacksonVersion"]}"))
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:${rootProject.extra["jacksonVersion"]}")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${rootProject.extra["jacksonVersion"]}")
}
