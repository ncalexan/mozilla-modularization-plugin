/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.13.0")
    implementation(gradleKotlinDsl())
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.tngtech.archunit:archunit:1.4.1")
    implementation("org.yaml:snakeyaml:2.2")
}

gradlePlugin {
    plugins {
        create("modularizationPlugin") {
            id = "org.mozilla.gradle.modularization"
            implementationClass = "ModularizationPlugin"
        }
    }
}
