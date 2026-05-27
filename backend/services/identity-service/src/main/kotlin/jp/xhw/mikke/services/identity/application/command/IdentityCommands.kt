package jp.xhw.mikke.services.identity.application.command

import jp.xhw.mikke.platform.auth.IssuedClientSession
import jp.xhw.mikke.services.identity.model.AvatarMediaId
import jp.xhw.mikke.services.identity.model.IdentityUser

data class RegisterIdentityUserCommand(
    val email: String,
    val username: String,
    val displayName: String,
    val password: String,
)

data class LoginIdentityUserCommand(
    val loginId: String,
    val password: String,
)

data class UpdateProfileCommand(
    val username: String?,
    val displayName: String?,
    val avatarMediaId: AvatarMediaId?,
)

data class AuthenticatedIdentityUser(
    val user: IdentityUser,
    val session: IssuedClientSession,
)

data class ChangePasswordCommand(
    val currentPassword: String,
    val newPassword: String,
)
