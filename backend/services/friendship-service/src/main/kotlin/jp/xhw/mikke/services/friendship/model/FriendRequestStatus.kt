package jp.xhw.mikke.services.friendship.model

enum class FriendRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELED,
    ;

    fun toDatabaseValue(): String = name

    companion object {
        fun fromDatabaseValue(value: String): FriendRequestStatus =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("unknown friend request status: $value")
    }
}
