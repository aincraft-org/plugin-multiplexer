package io.github.developmentnetwork.runtime.process

import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.TimeoutException

/** Polls a TCP endpoint until it accepts a connection or its deadline expires. */
class ReadinessProbe {
    fun await(host: String, port: Int, timeout: Duration) {
        require(host.isNotBlank()) { "Readiness host must not be blank" }
        require(port in 1..65535) { "Readiness port must be in 1..65535: $port" }
        require(!timeout.isNegative) { "Readiness timeout must not be negative" }

        val deadline = deadlineNanos(timeout)
        var lastFailure: Exception? = null
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining < 0L) {
                throw TimeoutException("Timed out waiting for TCP readiness at $host:$port")
            }
            val connectMillis = remaining.coerceAtMost(READ_ATTEMPT_MILLIS * 1_000_000L)
                .coerceAtLeast(1_000_000L) / 1_000_000L
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), connectMillis.toInt().coerceAtLeast(1))
                }
                return
            } catch (error: Exception) {
                if (error is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                lastFailure = error
            }
            if (System.nanoTime() >= deadline) {
                val detail = lastFailure?.message?.let { ": $it" } ?: ""
                throw TimeoutException("Timed out waiting for TCP readiness at $host:$port$detail")
            }
            try {
                Thread.sleep(RETRY_MILLIS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
    }

    private fun deadlineNanos(timeout: Duration): Long {
        val nanos = try {
            timeout.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val now = System.nanoTime()
        return if (nanos >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }

    private companion object {
        const val READ_ATTEMPT_MILLIS = 100L
        const val RETRY_MILLIS = 10L
    }
}
