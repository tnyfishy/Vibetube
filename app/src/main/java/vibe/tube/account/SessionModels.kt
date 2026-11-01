package vibe.tube.account

import kotlinx.serialization.Serializable

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAt: Long? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val hostOnly: Boolean = false,
)

@Serializable
data class AccountIdentity(
    val key: String,
    val pageId: String?,
    val name: String,
    val handle: String?,
    val channelId: String?,
    val avatarUrl: String?,
)

@Serializable
data class AccountSession(
    val id: String,
    val name: String,
    val handle: String?,
    val avatarUrl: String?,
    val identities: List<AccountIdentity> = emptyList(),
    val activeIdentityKey: String = PRIMARY_IDENTITY,
    val cookies: List<StoredCookie>,
    val visitorData: String? = null,
    val verifiedAt: Long? = null,
    val createdAt: Long,
) {
    val activeIdentity: AccountIdentity?
        get() = identities.firstOrNull { it.key == activeIdentityKey }

    val pageId: String?
        get() = activeIdentity?.pageId

    val scopeKey: String
        get() = "$id/$activeIdentityKey"

    override fun toString(): String = "AccountSession(id=$id, cookies=${cookies.size})"

    companion object {
        const val PRIMARY_IDENTITY = "primary"
    }
}

data class AccountContext(
    val accountId: String,
    val identityKey: String,
    val pageId: String?,
    val displayName: String,
) {
    val scopeKey: String get() = "$accountId/$identityKey"
}

sealed interface AuthState {
    data object Guest : AuthState
    data class Verifying(val accountId: String) : AuthState
    data class Verified(val accountId: String) : AuthState
    data class Expired(val accountId: String) : AuthState
    data class Unverified(val accountId: String, val reason: String) : AuthState
}

data class AccountTag(val accountId: String)
