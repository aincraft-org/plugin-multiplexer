import java.util.zip.ZipFile
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.TaskProvider

plugins {
    base
}

fun nestedBuild(name: String, directory: String): TaskProvider<GradleBuild> =
    tasks.register<GradleBuild>(name) {
        dir = file(directory)
        tasks = listOf("clean", "check", "assemble")
        startParameter.projectProperties.putAll(gradle.startParameter.projectProperties)
    }

val networkQuality = nestedBuild("networkQuality", "network")
val velocityPluginQuality = nestedBuild("velocityPluginQuality", "velocity-plugin")

val verifyVelocityPluginMetadata = tasks.register("verifyVelocityPluginMetadata") {
    dependsOn(velocityPluginQuality)
    doLast {
        val jars = fileTree("velocity-plugin/build/libs") {
            include("proxy-inspector-*.jar")
        }.files
        check(jars.size == 1) {
            "expected exactly one proxy-inspector jar, found ${jars.size}"
        }

        val jar = jars.single()
        val expectedVersion = jar.name
            .removePrefix("proxy-inspector-")
            .removeSuffix(".jar")
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("velocity-plugin.json")
                ?: error("$jar is missing velocity-plugin.json")
            val metadata = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            check(metadata.contains("\"id\": \"proxy-inspector\"")) {
                "$jar has the wrong plugin id"
            }
            check(metadata.contains("\"version\": \"$expectedVersion\"")) {
                "$jar metadata version does not match $expectedVersion"
            }
            check(metadata.contains("ProxyInspectorPlugin")) {
                "$jar metadata is missing the plugin main class"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(networkQuality, verifyVelocityPluginMetadata)
}

tasks.named("assemble") {
    dependsOn(networkQuality, velocityPluginQuality)
}
