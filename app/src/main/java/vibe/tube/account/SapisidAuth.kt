package vibe.tube.account

import java.security.MessageDigest

object SapisidAuth {
    const val ORIGIN = "https://www.youtube.com"

    fun authorization(jar: SessionCookieJar, nowSeconds: Long, origin: String = ORIGIN): String? {
        val sapisid = jar.valueOf("SAPISID") ?: jar.valueOf("__Secure-3PAPISID") ?: return null
        val first = jar.valueOf("__Secure-1PAPISID")
        val third = jar.valueOf("__Secure-3PAPISID")
        val parts = mutableListOf("SAPISIDHASH ${token(nowSeconds, sapisid, origin)}")
        if (first != null) parts += "SAPISID1PHASH ${token(nowSeconds, first, origin)}"
        if (third != null) parts += "SAPISID3PHASH ${token(nowSeconds, third, origin)}"
        return parts.joinToString(" ")
    }

    fun token(nowSeconds: Long, secret: String, origin: String): String =
        "${nowSeconds}_${sha1Hex("$nowSeconds $secret $origin")}"

    fun hasCredentials(jar: SessionCookieJar): Boolean =
        jar.valueOf("SAPISID") != null || jar.valueOf("__Secure-3PAPISID") != null

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
