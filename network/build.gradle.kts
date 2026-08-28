import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.gradle.jvm.tasks.Jar

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.0"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
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

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

evaluationDependsOn(":runtime")
val runtimeProject = project(":runtime")
val runtimeJar = runtimeProject.tasks.named<Jar>("jar")

tasks.named<Jar>("jar") {
    dependsOn(runtimeJar)
    from(runtimeJar) {
        into("META-INF/development-network")
        rename { "runtime.jar" }
    }
}

tasks.named("check") {
    dependsOn(runtimeProject.tasks.named("check"))
}

tasks.named("assemble") {
    dependsOn(runtimeJar)
}

tasks.test {
    useJUnitPlatform()
}