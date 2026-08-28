import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    java
}

group = "io.github.aincraft"

val calverDate = LocalDate.now(ZoneOffset.UTC)
    .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))

version = providers.gradleProperty("buildVersion")
    .orElse(
        providers.environmentVariable("GITHUB_RUN_NUMBER")
            .map { "$calverDate.$it" }
    )
    .orElse("$calverDate-SNAPSHOT")
    .get()
repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.1.1")
}

tasks.processResources {
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("proxy-inspector")
}
