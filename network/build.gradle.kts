import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.0"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

group = "io.github.development-network"

val calverDate = LocalDate.now(ZoneOffset.UTC)
    .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))

version = providers.gradleProperty("buildVersion")
    .orElse(
        providers.environmentVariable("GITHUB_RUN_NUMBER")
            .map { "$calverDate.$it" }
    )
    .orElse("$calverDate-SNAPSHOT")
    .get()

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