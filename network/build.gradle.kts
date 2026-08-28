plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.0"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

group = "io.github.development-network"
version = "1.0.0"

gradlePlugin {
    plugins {
        create("devNetwork") {
            id = "io.github.development-network"
            implementationClass = "io.github.developmentnetwork.DevNetworkPlugin"
        }
    }
}

repositories {
    mavenCentral()
}