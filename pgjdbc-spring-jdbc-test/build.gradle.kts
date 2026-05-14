plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

dependencies {
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    testImplementation(projects.testkit)

    testImplementation("org.springframework:spring-jdbc:6.1.1")
}
