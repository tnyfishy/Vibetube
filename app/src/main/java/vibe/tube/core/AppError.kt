package vibe.tube.core

sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(val httpCode: Int?, message: String, cause: Throwable? = null) : AppError(message, cause)
    class SessionExpired(val accountId: String?) : AppError("session_expired")
    class LoginRequired(val feature: String) : AppError("login_required:$feature")
    class Parse(val where: String, cause: Throwable? = null) : AppError("parse_failed:$where", cause)
    class Unsupported(val feature: String) : AppError("unsupported:$feature")
    class Rejected(val action: String, val detail: String?) : AppError("rejected:$action")
    class Playback(val reason: PlaybackFailure, val detail: String? = null) : AppError("playback:$reason")
    class Stale : AppError("stale_response")
}

enum class PlaybackFailure {
    LoginRequired,
    BotCheck,
    AgeRestricted,
    RegionBlocked,
    Private,
    Removed,
    Unavailable,
    LiveUnsupported,
    DrmUnsupported,
    NoCompatibleStream,
    CipherUnsupported,
    Expired,
}
