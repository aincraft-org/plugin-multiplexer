package io.github.developmentnetwork.runtime.config

import io.github.developmentnetwork.runtime.model.OwnershipMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
        forwardingSecret: String = SharedForwardingSecret.VALUE,
    ): PreflightResult {
        val component = "proxy"
        var result = if (isRegularFileNoFollow(configPath)) {
            verifyProxyFile(configPath, forwardingSecret)
        } else {
            val ownership = if (owned) "owned proxy may be regenerated" else "external proxy must be configured explicitly"
            PreflightResult.fail(component, "Proxy configuration is unknown at $configPath; $ownership; require offline modern forwarding")
        }
        if (!result.success && owned && regenerate != null) {
            result = try {
                regenerate()
                if (isRegularFileNoFollow(configPath)) {
                    verifyProxyFile(configPath, forwardingSecret)
                } else {
                    PreflightResult.fail(component, "Owned proxy regeneration did not create $configPath")
                }
            } catch (error: Exception) {
                PreflightResult.fail(component, "Owned proxy regeneration failed for $configPath: ${error.message ?: error::class.simpleName}")
            }
        }
        return result
    }

    fun verifyProxy(
        configPath: Path,
        mode: OwnershipMode,
        forwardingSecret: String = SharedForwardingSecret.VALUE,
    ): PreflightResult = verifyProxy(configPath, owned = mode == OwnershipMode.MANAGED, forwardingSecret = forwardingSecret)

    fun verifyPaper(
        workDir: Path,
        external: Boolean = false,
        forwardingSecret: String = SharedForwardingSecret.VALUE,
    ): PreflightResult {
        val component = if (external) "external Paper" else "managed Paper"
        val propertiesPath = workDir.resolve("server.properties")
        if (!isRegularFileNoFollow(propertiesPath)) {
            return PreflightResult.fail(component, "Paper configuration is unknown at $propertiesPath; require online-mode=false")
        }
        val properties = try {
            parseProperties(Files.readString(propertiesPath, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            return PreflightResult.fail(component, "$propertiesPath is invalid: ${error.message ?: error::class.simpleName}")
        }
        if (properties["online-mode"] != "false") {
            val detail = properties["online-mode"] ?: "unknown"
            return PreflightResult.fail(component, "$propertiesPath has $detail online-mode; require online-mode=false")
        }

        val paperPath = workDir.resolve("config/paper-global.yml")
        if (!isRegularFileNoFollow(paperPath)) {
            return PreflightResult.fail(component, "Paper forwarding configuration is unknown at $paperPath; require proxies.velocity modern forwarding")
        }
        val paper = try {
            parseYaml(Files.readString(paperPath, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            return PreflightResult.fail(component, "$paperPath is invalid: ${error.message ?: error::class.simpleName}")
        }
        val velocityPath = listOf("proxies", "velocity")
        if (paper.value(velocityPath + "enabled") != "true" ||
            paper.value(velocityPath + "online-mode") != "false"
        ) {
            return PreflightResult.fail(component, "$paperPath proxies.velocity does not enable Velocity modern forwarding with offline mode")
        }
        if (paper.value(velocityPath + "secret") != forwardingSecret) {
            return PreflightResult.fail(component, "$paperPath proxies.velocity has an unknown forwarding secret; require the shared development secret")
        }

        val spigotPath = workDir.resolve("spigot.yml")
        if (!isRegularFileNoFollow(spigotPath)) {
            return PreflightResult.fail(component, "BungeeCord forwarding configuration is unknown at $spigotPath; require settings.bungeecord: false")
        }
        val spigot = try {
            parseYaml(Files.readString(spigotPath, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            return PreflightResult.fail(component, "$spigotPath is invalid: ${error.message ?: error::class.simpleName}")
        }
        if (spigot.value(listOf("settings", "bungeecord")) != "false") {
            return PreflightResult.fail(component, "$spigotPath settings.bungeecord must be false")
        }
        return PreflightResult.pass(component)
    }

    fun verifyPaper(
        workDir: Path,
        mode: OwnershipMode,
        forwardingSecret: String = SharedForwardingSecret.VALUE,
    ): PreflightResult = verifyPaper(workDir, external = mode == OwnershipMode.EXTERNAL, forwardingSecret)

    private fun verifyProxyFile(configPath: Path, expectedSecret: String): PreflightResult {
        val component = "proxy"
        val toml = try {
            parseToml(Files.readString(configPath, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            return PreflightResult.fail(component, "$configPath is invalid: ${error.message ?: error::class.simpleName}")
        }
        val onlineMode = toml.value(listOf("online-mode"))
        if (onlineMode !in setOf("true", "false")) {
            val detail = onlineMode ?: "unknown"
            return PreflightResult.fail(component, "$configPath has $detail online mode; require online-mode = true or false")
        }
        if (toml.value(listOf("player-info-forwarding-mode")) != "modern") {
            return PreflightResult.fail(component, "$configPath does not enable modern forwarding; require player-info-forwarding-mode = \"modern\"")
        }
        val secretReference = toml.value(listOf("forwarding-secret-file"))
            ?: return PreflightResult.fail(component, "$configPath does not declare forwarding-secret-file; require the shared development secret")
        val secretPath = try {
            val reference = Path.of(secretReference)
            val base = configPath.parent ?: Path.of(".")
            if (reference.isAbsolute) reference else base.resolve(reference).normalize()
        } catch (error: Exception) {
            return PreflightResult.fail(component, "$configPath has an invalid forwarding-secret-file: ${error.message ?: error::class.simpleName}")
        }
        if (!isRegularFileNoFollow(secretPath)) {
            return PreflightResult.fail(component, "Forwarding secret is unknown at $secretPath; require the shared development secret")
        }
        val actualSecret = try {
            Files.readString(secretPath, StandardCharsets.UTF_8).trimEnd('\r', '\n')
        } catch (error: Exception) {
            return PreflightResult.fail(component, "Forwarding secret cannot be read at $secretPath: ${error.message ?: error::class.simpleName}")
        }
        if (actualSecret != expectedSecret) {
            return PreflightResult.fail(component, "$secretPath does not contain the expected shared forwarding secret")
        }
        return PreflightResult.pass(component)
    }

    private fun parseProperties(content: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0) { "line ${index + 1} must use key=value" }
            val key = line.substring(0, separator).trim()
            require(PROPERTY_KEY.matches(key)) { "line ${index + 1} has invalid property key" }
            require(!result.containsKey(key)) { "duplicate property '$key'" }
            result[key] = line.substring(separator + 1).trim()
        }
        return result
    }

    private fun parseYaml(content: String): YamlDocument {
        val values = LinkedHashMap<List<String>, String?>()
        val stack = ArrayList<YamlNode>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            if (rawLine.contains('\t')) throw IllegalArgumentException("line ${index + 1} contains a tab")
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEachIndexed
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }
            val separator = trimmed.indexOf(':')
            require(separator > 0) { "line ${index + 1} must use key: value" }
            val key = trimmed.substring(0, separator).trim()
            require(YAML_KEY.matches(key)) { "line ${index + 1} has invalid YAML key" }
            while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeAt(stack.lastIndex)
            val path = stack.map { it.key } + key
            require(!values.containsKey(path)) { "duplicate YAML property '${path.joinToString(".")}'" }
            val rawValue = trimmed.substring(separator + 1).trim()
            values[path] = rawValue.takeUnless { it.isEmpty() }?.let(::yamlScalar)
            if (rawValue.isEmpty()) stack += YamlNode(indent, key)
        }
        return YamlDocument(values)
    }

    private fun parseToml(content: String): TomlDocument {
        val values = LinkedHashMap<List<String>, String>()
        var section = emptyList<String>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line.startsWith('[') && line.endsWith(']')) {
                val name = line.substring(1, line.length - 1).trim()
                require(name.isNotEmpty() && TOML_SECTION.matches(name)) { "line ${index + 1} has invalid TOML section" }
                section = name.split('.').map(String::trim)
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "line ${index + 1} must use key = value" }
            val key = line.substring(0, separator).trim()
            require(TOML_KEY.matches(key)) { "line ${index + 1} has invalid TOML key" }
            val path = section + key
            require(!values.containsKey(path)) { "duplicate TOML property '${path.joinToString(".")}'" }
            values[path] = tomlScalar(line.substring(separator + 1).trim())
        }
        return TomlDocument(values)
    }

    private fun yamlScalar(value: String): String {
        if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))) {
            return value.substring(1, value.length - 1)
        }
        return value
    }

    private fun tomlScalar(value: String): String {
        require(value.isNotEmpty()) { "TOML value must not be empty" }
        if (value.first() != '"') return value.substringBefore(" #").trim()
        require(value.length >= 2 && value.last() == '"') { "TOML string must be closed" }
        return value.substring(1, value.length - 1)
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
    }

    private fun isRegularFileNoFollow(path: Path): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private data class YamlNode(val indent: Int, val key: String)
    private data class YamlDocument(val values: Map<List<String>, String?>) {
        fun value(path: List<String>): String? = values[path]
    }
    private data class TomlDocument(val values: Map<List<String>, String>) {
        fun value(path: List<String>): String? = values[path]
    }

    private companion object {
        val PROPERTY_KEY = Regex("[A-Za-z0-9_.-]+")
        val YAML_KEY = Regex("[A-Za-z0-9_.-]+")
        val TOML_KEY = Regex("[A-Za-z0-9_-]+")
        val TOML_SECTION = Regex("[A-Za-z0-9_.-]+")
    }
}
