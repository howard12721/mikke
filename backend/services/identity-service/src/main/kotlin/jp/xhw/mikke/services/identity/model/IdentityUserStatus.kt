package jp.xhw.mikke.services.identity.model

enum class IdentityUserStatus {
    ACTIVE,
    SUSPENDED,
    DEACTIVATED,
    ;

    companion object {
        fun fromDatabaseValue(value: String): IdentityUserStatus =
            when (value.uppercase()) {
                "ACTIVE" -> ACTIVE
                "SUSPENDED" -> SUSPENDED
                "DEACTIVATED" -> DEACTIVATED
                else -> error("Unsupported identity user status: $value")
            }
    }

    fun toDatabaseValue(): String = name
}
