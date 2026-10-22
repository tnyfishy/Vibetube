package vibe.tube.core

import java.security.MessageDigest

object Redact {
    private val sensitiveHeaders = setOf("cookie", "set-cookie", "authorization", "x-goog-visitor-id", "x-goog-pageid", "x-origin")

    fun isSensitiveHeader(name: String): Boolean = name.lowercase() in sensitiveHeaders

    fun anonymize(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
