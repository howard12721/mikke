package jp.xhw.mikke.services.friendship.model

enum class FriendshipStatus {
    ACTIVE,
    REMOVED,
    ;

    fun toDatabaseValue(): String = name

    companion object {
        fun fromDatabaseValue(value: String): FriendshipStatus =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("unknown friendship status: $value")
    }
}
