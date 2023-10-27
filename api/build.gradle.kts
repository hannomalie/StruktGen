plugins {
    kotlin("jvm")
    `maven-publish`
}


repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType(org.jetbrains.kotlin.gradle.dsl.KotlinCompile::class) {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
        }
    }
}
