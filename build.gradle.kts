plugins {
    kotlin("jvm") version "1.9.21" apply false
    id("com.google.devtools.ksp") version "1.9.21-1.0.15" apply false
}

allprojects {
    group = "de.hanno.struktgen"
}

repositories {
    mavenCentral()
}
