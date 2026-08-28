package io.github.developmentnetwork.runtime.config

import io.github.developmentnetwork.runtime.state.AtomicFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Writes deterministic level-4 operator entries for offline development accounts. */
class OpsWriter {
    fun write(workDir: Path, users: List<String>): Path {
        require(Files.isDirectory(workDir)) { "Paper work directory does not exist: $workDir" }
        users.forEach { user ->
            require(USER_PATTERN.matches(user)) {
                "Invalid development username '$user'; expected 1-16 ASCII letters, digits, or underscore"
            }
        }
        val content = buildString {
            append('[')
            users.forEachIndexed { index, user ->
                if (index > 0) append(",")
                append("\n  {\n")
                append("    \"uuid\": \"${offlineUuid(user)}\",\n")
                append("    \"name\": \"$user\",\n")
                append("    \"level\": 4,\n")
                append("    \"bypassesPlayerLimit\": false\n")
                append("  }")
            }
            if (users.isNotEmpty()) append('\n')
            append(']')
        }
        val destination = workDir.resolve("ops.json")
        AtomicFiles.write(destination, content)
        return destination
    }

    private fun offlineUuid(name: String): UUID {
        // Keep the exact Java UUID.nameUUIDFromBytes algorithm explicit: MD5 digest,
        // RFC 4122 version 3 and IETF variant bits, then UUID's two 64-bit halves.
        val digest = MessageDigest.getInstance("MD5")
            .digest("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))
        digest[6] = (digest[6].toInt() and 0x0f or 0x30).toByte()
        digest[8] = (digest[8].toInt() and 0x3f or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (index in 0 until 8) most = (most shl 8) or (digest[index].toLong() and 0xff)
        for (index in 8 until 16) least = (least shl 8) or (digest[index].toLong() and 0xff)
        return UUID(most, least)
    }

    private companion object {
        val USER_PATTERN = Regex("[A-Za-z0-9_]{1,16}")
    }
}
