package io.github.developmentnetwork.runtime.process

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Polls a TCP endpoint until it accepts a connection or its deadline expires.
 * Hostname resolution is performed in a daemon task with the same deadline as
 * connection attempts, so DNS cannot extend the caller's timeout.
 */
class ReadinessProbe {
    fun await(host: String, port: Int, timeout: Duration) {
        require(host.isNotBlank()) { "Readiness host must not be blank" }
        require(port in 1..65535) { "Readiness port must be in 1..65535: $port" }
        require(!timeout.isNegative) { "Readiness timeout must not be negative" }
        checkInterrupted()

        val deadline = deadlineNanos(timeout)
        val addresses = resolve(host, deadline)
        var lastFailure: Exception? = null
        while (true) {
            checkInterrupted()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw timeout(host, port, lastFailure)
            val connectMillis = millisFor(remaining).coerceAtMost(READ_ATTEMPT_MILLIS)
            for (address in addresses) {
                checkInterrupted()
                val addressRemaining = deadline - System.nanoTime()
                if (addressRemaining <= 0L) throw timeout(host, port, lastFailure)
                val attemptMillis = millisFor(addressRemaining).coerceAtMost(connectMillis).coerceAtLeast(1L)
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), attemptMillis.toInt())
                    }
                    return
                } catch (error: Exception) {
                    lastFailure = error
                }
            }
            if (System.nanoTime() >= deadline) throw timeout(host, port, lastFailure)
            try {
                Thread.sleep(RETRY_MILLIS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
    }

    private fun resolve(host: String, deadline: Long): List<InetAddress> {
        val task = FutureTask { InetAddress.getAllByName(host).toList() }
        val resolver = Thread(task, "runtime-readiness-resolver").apply { isDaemon = true }
        resolver.start()
        try {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Readiness timeout expired during hostname resolution")
            return task.get(remaining, TimeUnit.NANOSECONDS).also {
                if (it.isEmpty()) throw IllegalStateException("Hostname resolved to no addresses: $host")
            }
        } catch (error: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            throw TimeoutException("Timed out resolving readiness host: $host")
        } catch (error: ExecutionException) {
            task.cancel(true)
            val cause = error.cause
            throw timeout(host, 0, cause as? Exception)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Readiness probe interrupted")
        }
    }

    private fun timeout(host: String, port: Int, lastFailure: Exception?): TimeoutException {
        val detail = lastFailure?.message?.let { ": $it" } ?: ""
        return TimeoutException("Timed out waiting for TCP readiness at $host:$port$detail")
    }

    private fun millisFor(nanos: Long): Long {
        val rounded = if (nanos > Long.MAX_VALUE - 999_999L) Long.MAX_VALUE else nanos + 999_999L
        return (rounded / 1_000_000L).coerceAtLeast(1L)
    }

    private fun deadlineNanos(timeout: Duration): Long {
        val nanos = try {
            timeout.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val now = System.nanoTime()
        return if (nanos >= 0L && now > Long.MAX_VALUE - nanos) Long.MAX_VALUE else now + nanos
    }

    private companion object {
        const val READ_ATTEMPT_MILLIS = 100L
        const val RETRY_MILLIS = 10L
    }
}
