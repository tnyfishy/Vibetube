package vibe.tube.account

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.core.Clock
import java.io.File
import java.security.MessageDigest

class SessionPrimitivesTest {

    private fun cookie(name: String, value: String, domain: String = "youtube.com", expires: Long? = null) =
        StoredCookie(name, value, domain, "/", expires, secure = true)

    private fun built(name: String, value: String, expiresAt: Long) =
        okhttp3.Cookie.Builder().name(name).value(value).domain("youtube.com").path("/").secure().expiresAt(expiresAt).build()

    @Test
    fun sapisidHashMatchesDefinition() {
        val jar = SessionCookieJar(listOf(cookie("SAPISID", "abc123")))
        val header = SapisidAuth.authorization(jar, 1700000000)!!
        val expected = MessageDigest.getInstance("SHA-1")
            .digest("1700000000 abc123 https://www.youtube.com".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("SAPISIDHASH 1700000000_$expected", header)
    }

    @Test
    fun authorizationIncludesFirstAndThirdPartyHashes() {
        val jar = SessionCookieJar(
            listOf(cookie("SAPISID", "a"), cookie("__Secure-1PAPISID", "b"), cookie("__Secure-3PAPISID", "c")),
        )
        val header = SapisidAuth.authorization(jar, 10)!!
        assertTrue(header.startsWith("SAPISIDHASH 10_"))
        assertTrue(header.contains(" SAPISID1PHASH 10_"))
        assertTrue(header.contains(" SAPISID3PHASH 10_"))
    }

    @Test
    fun noCredentialsMeansNoAuthorization() {
        val jar = SessionCookieJar(listOf(cookie("VISITOR_INFO1_LIVE", "x")))
        assertNull(SapisidAuth.authorization(jar, 10))
        assertFalse(SapisidAuth.hasCredentials(jar))
    }

    @Test
    fun jarOnlySendsCookiesToMatchingSecureHosts() {
        val jar = SessionCookieJar(listOf(cookie("SID", "1"), cookie("X", "2", domain = "google.com")))
        assertEquals(setOf("SID"), jar.loadForRequest("https://www.youtube.com/youtubei/v1/browse".toHttpUrl()).map { it.name }.toSet())
        assertTrue(jar.loadForRequest("http://www.youtube.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        assertEquals(setOf("X"), jar.loadForRequest("https://accounts.google.com/".toHttpUrl()).map { it.name }.toSet())
    }

    @Test
    fun jarRejectsForeignDomainsOnLoad() {
        val jar = SessionCookieJar(listOf(cookie("evil", "1", domain = "example.com")))
        assertTrue(jar.snapshot().isEmpty())
    }

    @Test
    fun expiredCookiesAreNotSentAndSetCookieUpdatesAndDeletes() {
        var now = 1_000L
        var changes = 0
        val jar = SessionCookieJar(
            listOf(cookie("OLD", "v", expires = 500L), cookie("KEEP", "v", expires = 10_000L)),
            Clock { now },
        ) { changes++ }
        val url = "https://www.youtube.com/".toHttpUrl()
        assertEquals(listOf("KEEP"), jar.loadForRequest(url).map { it.name })
        val rotated = built("KEEP", "new", expiresAt = now + 100_000)
        jar.saveFromResponse(url, listOf(rotated))
        assertEquals("new", jar.valueOf("KEEP"))
        assertEquals(1, changes)
        val deletion = built("KEEP", "", expiresAt = now - 1)
        jar.saveFromResponse(url, listOf(deletion))
        assertNull(jar.valueOf("KEEP"))
        now = 2_000L
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun twoJarsAreIsolated() {
        val a = SessionCookieJar(listOf(cookie("SAPISID", "alice")))
        val b = SessionCookieJar(listOf(cookie("SAPISID", "bob")))
        val url = "https://www.youtube.com/".toHttpUrl()
        a.saveFromResponse(url, listOf(built("SID", "only-alice", expiresAt = 99_999_999_999_999)))
        assertEquals("alice", a.valueOf("SAPISID"))
        assertEquals("bob", b.valueOf("SAPISID"))
        assertNull(b.valueOf("SID"))
        assertNotEquals(SapisidAuth.authorization(a, 5), SapisidAuth.authorization(b, 5))
    }

    @Test
    fun webViewCookieHeaderIsParsed() {
        val cookies = SessionCookieJar.fromWebViewHeader("SID=1; SAPISID=a=b; HSID=3", "youtube.com", 0)
        assertEquals(listOf("SID", "SAPISID", "HSID"), cookies.map { it.name })
        assertEquals("a=b", cookies[1].value)
        assertTrue(cookies.all { it.domain == "youtube.com" && it.secure })
    }

    @Test
    fun cipherRoundTripAndTamperDetection() {
        val cipher = InMemorySecretCipher()
        val blob = cipher.encrypt("secret".toByteArray())
        assertEquals("secret", String(cipher.decrypt(blob)))
        assertFalse(String(blob).contains("secret"))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        val failed = runCatching { cipher.decrypt(blob) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun vaultPersistsEncryptedAndDeletesOnRequest() {
        val dir = File.createTempFile("vault", "").apply { delete(); mkdirs() }
        val cipher = InMemorySecretCipher()
        val vault = FileSessionVault(dir, cipher)
        val session = AccountSession(
            id = "acc1", name = "N", handle = null, avatarUrl = null,
            cookies = listOf(cookie("SAPISID", "TOPSECRETVALUE")), createdAt = 1,
        )
        vault.save(session)
        vault.saveActiveId("acc1")
        val raw = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(raw.contains("TOPSECRETVALUE"))
        assertFalse(raw.contains("SAPISID"))
        assertEquals("acc1", vault.loadActiveId())
        assertEquals(listOf("acc1"), vault.loadAll().map { it.id })
        vault.delete("acc1")
        assertTrue(vault.loadAll().isEmpty())
        vault.wipe()
        assertNull(vault.loadActiveId())
        dir.deleteRecursively()
    }

    @Test
    fun corruptVaultFileIsDiscardedNotCrashing() {
        val dir = File.createTempFile("vault", "").apply { delete(); mkdirs() }
        File(dir, "bad.session").writeBytes(ByteArray(40) { 7 })
        val vault = FileSessionVault(dir, InMemorySecretCipher())
        assertTrue(vault.loadAll().isEmpty())
        assertFalse(File(dir, "bad.session").exists())
        dir.deleteRecursively()
    }

    @Test
    fun sessionToStringDoesNotLeakCookies() {
        val session = AccountSession(
            id = "x", name = "N", handle = null, avatarUrl = null,
            cookies = listOf(cookie("SAPISID", "LEAKME")), createdAt = 1,
        )
        assertFalse(session.toString().contains("LEAKME"))
    }
}
