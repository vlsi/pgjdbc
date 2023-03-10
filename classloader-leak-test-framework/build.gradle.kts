plugins {
    id("build-logic.java-library")
}

group = "se.jiderhamn"

dependencies {
    api("junit:junit:4.13.2")
    api("org.apache.bcel:bcel:6.7.0")
}
