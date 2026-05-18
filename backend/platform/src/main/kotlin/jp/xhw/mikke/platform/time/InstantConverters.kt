package jp.xhw.mikke.platform.time

import com.google.protobuf.Timestamp
import kotlin.time.Instant

fun Instant.toJavaInstant(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

fun java.time.Instant.toKotlinInstant(): Instant = Instant.fromEpochSeconds(epochSecond, nano)

fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp
        .newBuilder()
        .setSeconds(epochSeconds)
        .setNanos(nanosecondsOfSecond)
        .build()

fun Timestamp.toKotlinInstant(): Instant = Instant.fromEpochSeconds(seconds, nanos)

fun Instant.toEpochMicros(): Long = epochSeconds * 1_000_000L + nanosecondsOfSecond / 1_000

fun epochMicrosToInstant(epochMicros: Long): Instant {
    val seconds = epochMicros / 1_000_000L
    val micros = epochMicros % 1_000_000L
    return Instant.fromEpochSeconds(seconds, (micros * 1_000).toInt())
}
