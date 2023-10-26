plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.10-1.0.13")
    implementation(project(":api"))
}

tasks.withType(org.jetbrains.kotlin.gradle.dsl.KotlinCompile::class) {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
    }
}
