package jp.xhw.mikke.platform.uuid.exposed

import jp.xhw.mikke.platform.uuid.toBinary16
import jp.xhw.mikke.platform.uuid.toUuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

class UuidBinaryColumnType(
    nullable: Boolean = false,
) : ColumnType<Uuid>(nullable) {
    override fun sqlType(): String = "BINARY(16)"

    override fun valueFromDB(value: Any): Uuid =
        when (value) {
            is ByteArray -> value.toUuid()
            is java.nio.ByteBuffer -> {
                val buffer = value.duplicate()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bytes.toUuid()
            }
            else -> error("Unexpected UUID binary value: ${value::class.qualifiedName}")
        }

    override fun notNullValueToDB(value: Uuid): Any = value.toBinary16()
}

fun Table.uuidBinary(name: String): Column<Uuid> = registerColumn(name, UuidBinaryColumnType())

fun Table.uuidBinaryNullable(name: String): Column<Uuid?> = registerColumn(name, UuidBinaryColumnType(nullable = true))
