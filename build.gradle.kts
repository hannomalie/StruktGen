plugins {
    kotlin("jvm") version "1.9.10" apply false
    `maven-publish`
}

allprojects {
    group = "de.hanno.struktgen"
}

repositories {
    mavenCentral()
}
