package vibe.tube.core

import java.security.MessageDigest

object Redact {
    fun anonymize(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
