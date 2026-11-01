package vibe.tube.youtube.player

import java.net.URLDecoder

/** Why googlevideo refused a stream URL, read from the URL itself. */
enum class StreamDenial {
    /** The URL's own `expire` deadline has passed; minting a fresh URL under the same client fixes it. */
    Expired,

    /** The deadline has not passed and the host still said no: the client is being refused, so re-minting under it is pointless. */
    Refused,

    /** No readable deadline; the cause cannot be told. */
    Unknown,
}

/** What a refusal on a still-valid URL says, read from whether the URL carried a proof-of-origin token. */
enum class StreamDenialKind {
    /** The URL is at or past its deadline (with clock-skew grace): a fresh URL from the same client fixes it. */
    UrlExpired,

    /** Valid deadline and no `pot`: the host enforces attestation on this client. */
    AttestationGated,

    /** Valid deadline and a `pot` was present: the token itself was refused. */
    TokenRejected,

    Unknown,
}

object StreamDenialClassifier {
    fun classify(url: String?, nowSeconds: Long): StreamDenial {
        val expire = param(url, "expire")?.toLongOrNull() ?: return StreamDenial.Unknown
        return if (nowSeconds >= expire) StreamDenial.Expired else StreamDenial.Refused
    }

    /** Within this many seconds of its deadline a URL counts as expired, covering clock skew and classification delay. */
    private const val EXPIRY_GRACE_SECONDS = 30L

    fun kind(url: String?, nowSeconds: Long): StreamDenialKind {
        val expire = param(url, "expire")?.toLongOrNull() ?: return StreamDenialKind.Unknown
        if (expire - nowSeconds <= EXPIRY_GRACE_SECONDS) return StreamDenialKind.UrlExpired
        return if (param(url, "pot").isNullOrEmpty()) StreamDenialKind.AttestationGated else StreamDenialKind.TokenRejected
    }

    /** The minting client's name, as googlevideo reports it in the `c` query parameter. */
    fun clientOf(url: String?): String? = param(url, "c")?.takeIf { it.isNotBlank() }?.uppercase()

    private fun param(url: String?, name: String): String? {
        if (url.isNullOrEmpty()) return null
        val query = url.substringAfter('?', "").substringBefore('#')
        return query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }
}
