import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("kapt")
    id("com.google.devtools.ksp") version "1.6.21-1.0.5"
    id( "me.champeau.jmh") version "0.6.4"
}


repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":processor"))
    implementation("org.openjdk.jmh:jmh-core:1.21")
    implementation(project(":api"))
    ksp("org.openjdk.jmh:jmh-generator-annprocess:1.21")
    ksp(project(":processor"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.7.0")
    testImplementation("org.assertj:assertj-core:3.19.0")
    jmh("org.lwjgl:lwjgl:3.2.3")
    jmhRuntimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    sourceSets["main"].apply {
        kotlin.srcDir("build/generated/ksp/main/kotlin/")
    }
}

tasks.withType(KotlinCompile::class) {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
    }
}
