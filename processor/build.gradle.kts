plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.21-1.0.15")
    implementation(project(":api"))
}

tasks.withType(org.jetbrains.kotlin.gradle.dsl.KotlinCompile::class) {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
    }
}

publishing {
    publications {
        create<MavenPublication>("processor") {
            from(components["java"])
        }
    }
}