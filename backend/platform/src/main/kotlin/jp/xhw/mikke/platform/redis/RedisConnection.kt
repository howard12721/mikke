@file:Suppress("ktlint:standard:filename")

package jp.xhw.mikke.platform.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

class RedisConnectionHandle(
    val client: RedisClient,
    val connection: StatefulRedisConnection<String, String>,
) : AutoCloseable {
    override fun close() {
        try {
            connection.close()
        } finally {
            client.shutdown()
        }
    }
}

fun connectRedisFromEnv(
    defaultHost: String = "localhost",
    defaultPort: Int = 6379,
): RedisConnectionHandle {
    val host = System.getenv("REDIS_HOST") ?: defaultHost
    val port = System.getenv("REDIS_PORT")?.toIntOrNull() ?: defaultPort
    val password = System.getenv("REDIS_PASSWORD")?.takeIf { it.isNotEmpty() }

    val uriBuilder =
        RedisURI
            .builder()
            .withHost(host)
            .withPort(port)

    password?.let(uriBuilder::withPassword)

    val client = RedisClient.create(uriBuilder.build())

    return try {
        RedisConnectionHandle(client = client, connection = client.connect())
    } catch (throwable: Throwable) {
        client.shutdown()
        throw throwable
    }
}
