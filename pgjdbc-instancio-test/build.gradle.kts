/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

// Instancio 6.x requires Java 17+ (the upcoming baseline), and even 5.x property-style
// generators are most useful there. The whole build otherwise defaults to JVM 8, so this
// module is only included for jdkTestVersion >= 17 (see settings.gradle.kts) and compiles
// its tests at release 17 — mirroring pgjdbc-spring-jdbc-test.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    // Instancio drives the random/edge data generation for the codec property tests.
    // 5.6.0 is the latest stable (6.x is still RC); bump to 6.x once it is GA.
    testImplementation("org.instancio:instancio-junit:5.6.0")
}
