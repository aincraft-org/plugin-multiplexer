import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "io.github.development-network"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

application {
    mainClass.set("io.github.developmentnetwork.runtime.RuntimeMainKt")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("runtime")
    archiveFileName.set("runtime.jar")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "io.github.developmentnetwork.runtime.RuntimeMainKt"
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { file ->
        if (file.isDirectory) file else zipTree(file)
    })
}
