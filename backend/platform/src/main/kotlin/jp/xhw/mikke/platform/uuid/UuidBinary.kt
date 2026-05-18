package jp.xhw.mikke.platform.uuid

import java.nio.ByteBuffer
import java.util.UUID
import kotlin.uuid.Uuid

private const val UUID_BINARY_LENGTH = 16

fun Uuid.toBinary16(): ByteArray {
    val javaUuid = UUID.fromString(toString())
    return ByteBuffer
        .allocate(UUID_BINARY_LENGTH)
        .putLong(javaUuid.mostSignificantBits)
        .putLong(javaUuid.leastSignificantBits)
        .array()
}

fun ByteArray.toUuid(): Uuid {
    require(size == UUID_BINARY_LENGTH) { "UUID binary must be $UUID_BINARY_LENGTH bytes, got $size" }
    val buffer = ByteBuffer.wrap(this)
    val javaUuid = UUID(buffer.long, buffer.long)
    return Uuid.parse(javaUuid.toString())
}

fun UUID.toKotlinUuid(): Uuid = Uuid.parse(toString())

fun Uuid.toJavaUuid(): UUID = UUID.fromString(toString())
