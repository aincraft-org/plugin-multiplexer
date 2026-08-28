package io.github.developmentnetwork.runtime.config

import io.github.developmentnetwork.runtime.model.OwnershipMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Structured result so callers can report every failed endpoint without changing files. */
data class PreflightResult(
    val component: String,
    val success: Boolean,
    val message: String,
) {
    val passed: Boolean get() = success
    val ok: Boolean get() = success
    val isSuccess: Boolean get() = success

    companion object {
        fun pass(component: String): PreflightResult = PreflightResult(component, true, "")
        fun fail(component: String, message: String): PreflightResult = PreflightResult(component, false, message)
    }
}

/** Verifies offline authentication and modern forwarding independently per component. */
class OfflinePreflight {
    fun verifyProxy(
        configPath: Path,
        owned: Boolean = false,
        regenerate: (() -> Unit)? = null,
    ): PreflightResult {
        val component = "proxy"
        if (!Files.isRegularFile(configPath)) {
            val ownership = if (owned) "owned proxy may be regenerated" else "external proxy must be configured explicitly"
            return PreflightResult.fail(component, "Proxy configuration is unknown at $configPath; $ownership; require online-mode = false")
        }
        var result = verifyProxyFile(configPath)
        if (!result.success && owned && regenerate != null) {
            regenerate()
            result = if (Files.isRegularFile(configPath)) verifyProxyFile(configPath)
            else PreflightResult.fail(component, "Owned proxy regeneration did not create $configPath")
        }
        return result
    }

    fun verifyProxy(configPath: Path, mode: OwnershipMode): PreflightResult =
        verifyProxy(configPath, owned = mode == OwnershipMode.MANAGED)

    fun verifyPaper(
        workDir: Path,
        external: Boolean = false,
        forwardingSecret: String = "dev-local-forwarding-secret-change-me",
    ): PreflightResult {
        val component = if (external) "external Paper" else "managed Paper"
        val propertiesPath = workDir.resolve("server.properties")
        if (!Files.isRegularFile(propertiesPath)) {
            return PreflightResult.fail(component, "Paper configuration is unknown at $propertiesPath; require online-mode=false")
        }
        val properties = Files.readString(propertiesPath, StandardCharsets.UTF_8)
        val offline = exactSetting(properties, "online-mode")
        if (offline != "false") {
            val detail = if (offline == null) "unknown" else "online"
            return PreflightResult.fail(component, "$propertiesPath has $detail online-mode; require online-mode=false")
        }

        val paperPath = workDir.resolve("config/paper-global.yml")
        if (!Files.isRegularFile(paperPath)) {
            return PreflightResult.fail(component, "Paper forwarding configuration is unknown at $paperPath; require Velocity modern forwarding")
        }
        val paper = Files.readString(paperPath, StandardCharsets.UTF_8)
        if (!Regex("(?m)^\\s*enabled\\s*:\\s*true\\s*$").containsMatchIn(paper) ||
            !Regex("(?m)^\\s*online-mode\\s*:\\s*false\\s*$").containsMatchIn(paper)
        ) {
            return PreflightResult.fail(component, "$paperPath does not enable Velocity modern forwarding with offline mode")
        }
        val secret = Regex("(?m)^\\s*secret\\s*:\\s*([\"']?)([^\"'\\r\\n]+)\\1\\s*$")
            .find(paper)?.groupValues?.getOrNull(2)
        if (secret != forwardingSecret) {
            return PreflightResult.fail(component, "$paperPath has an unknown forwarding secret; require the shared development secret")
        }

        val spigotPath = workDir.resolve("spigot.yml")
        if (!Files.isRegularFile(spigotPath)) {
            return PreflightResult.fail(component, "BungeeCord forwarding configuration is unknown at $spigotPath; require settings.bungeecord: false")
        }
        val spigot = Files.readString(spigotPath, StandardCharsets.UTF_8)
        if (!Regex("""(?m)^\s*bungeecord\s*:\s*false\s*$""").containsMatchIn(spigot)) {
            return PreflightResult.fail(component, "$spigotPath must contain settings.bungeecord: false")
        }
        return PreflightResult.pass(component)
    }

    fun verifyPaper(workDir: Path, mode: OwnershipMode, forwardingSecret: String = "dev-local-forwarding-secret-change-me"):
        PreflightResult = verifyPaper(workDir, external = mode == OwnershipMode.EXTERNAL, forwardingSecret)

    private fun verifyProxyFile(configPath: Path): PreflightResult {
        val content = Files.readString(configPath, StandardCharsets.UTF_8)
        return when (exactSetting(content, "online-mode")) {
            "false" -> PreflightResult.pass("proxy")
            "true" -> PreflightResult.fail("proxy", "$configPath has online-mode=true; require online-mode = false")
            else -> PreflightResult.fail("proxy", "$configPath has unknown online mode; require online-mode = false")
        }
    }

    private fun exactSetting(content: String, key: String): String? =
        Regex("(?m)^\\s*${Regex.escape(key)}\\s*=\\s*(true|false)\\s*$")
            .find(content)?.groupValues?.getOrNull(1)
}
