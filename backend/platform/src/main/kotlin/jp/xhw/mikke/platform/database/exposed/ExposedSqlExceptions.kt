package jp.xhw.mikke.platform.database.exposed

import java.sql.SQLException

fun Throwable.isUniqueConstraintViolation(): Boolean =
    when (this) {
        is SQLException -> isDuplicateKeySqlState(sqlState) || (nextException?.isUniqueConstraintViolation() == true)
        else -> cause?.isUniqueConstraintViolation() == true
    }

private fun isDuplicateKeySqlState(sqlState: String?): Boolean =
    when (sqlState) {
        "23000", // SQL standard integrity constraint violation
        "23505", // PostgreSQL / H2 duplicate key
        -> true

        else -> false
    }
