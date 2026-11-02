import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    id("dev.arbjerg.lavalink.gradle-plugin") version "1.0.15"
    `maven-publish`
}

// Tunnel by Pawan
group = "com.github.pawan"
// JitPack exposes the git tag as $VERSION
version = (System.getenv("VERSION") ?: "1.0.0").removePrefix("v")

lavalinkPlugin {
    name = "tunnel"
    path = "com.pawan.tunnel"
    apiVersion = "4.1.1"
    serverVersion = "4.1.1"
}

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
    maven("https://maven.lavalink.dev/snapshots")
    maven("https://jitpack.io")
}

dependencies {
    // All of these are provided by the Lavalink server at runtime
    compileOnly("org.springframework.boot:spring-boot-starter-web:3.4.1")
    compileOnly("dev.arbjerg:lavaplayer:2.2.4")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Tunnel",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Pawan"
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("tunnel") {
            from(components["java"])
            artifactId = "tunnel"
        }
    }
}

// One command builds the closed-source release package (jar + README + exp.yml, NO source code):
//   gradle releaseZip   ->   build/release/Tunnel-<version>-release.zip
val releaseZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds Tunnel-<version>-release.zip (compiled jar, README, exp.yml, license)"
    val root = "Tunnel-${project.version}"
    archiveFileName.set("$root-release.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    from(tasks.jar) { into("$root/plugins") }
    from("release/README.md") { into(root) }
    from("release/LICENSE.txt") { into(root) }
    from("exp.yml") { into(root) }
}
