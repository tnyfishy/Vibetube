package vibe.tube.youtube

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.youtube.potoken.BotGuardCodec
import java.util.Base64

class BotGuardCodecTest {
    private fun scramble(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) bytes[i] = ((bytes[i].toInt() and 0xFF) - 97).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun descrambleUndoesTheByteShift() {
        val plain = """["id",null,null,"hash","program","glob"]"""
        assertEquals(plain, BotGuardCodec.descramble(scramble(plain)))
    }

    @Test
    fun base64AcceptsBothAlphabetsAndDotPadding() {
        val data = byteArrayOf(-5, -1, -2, 62, 63, 0)
        val standard = Base64.getEncoder().encodeToString(data)
        val urlSafe = Base64.getUrlEncoder().encodeToString(data)
        assertArrayEquals(data, BotGuardCodec.decodeBase64(standard))
        assertArrayEquals(data, BotGuardCodec.decodeBase64(urlSafe))
        assertArrayEquals(data, BotGuardCodec.decodeBase64(urlSafe.replace('=', '.')))
    }

    @Test
    fun tokenKeepsPaddingAndUsesTheUrlSafeAlphabet() {
        val token = BotGuardCodec.encodeToken(byteArrayOf(-5, -1, -2, 62, 63))
        assertTrue(token, '+' !in token && '/' !in token)
        assertTrue(token, token.endsWith("="))
    }

    @Test
    fun scrambledChallengeIsReadFieldByField() {
        val payload = """["msg",[null,null,"function(){}"],[null,null,"//www.google.com/js/th/x.js"],"hash","PROGRAM","botguard_global"]"""
        val challenge = BotGuardCodec.parseChallenge("""["msg","${scramble(payload)}"]""")
        assertEquals("msg", challenge.messageId)
        assertEquals("function(){}", challenge.script)
        assertEquals("//www.google.com/js/th/x.js", challenge.scriptUrl)
        assertEquals("PROGRAM", challenge.program)
        assertEquals("botguard_global", challenge.globalName)
    }

    @Test
    fun plainChallengeIsReadFromTheFirstSlot() {
        val challenge = BotGuardCodec.parseChallenge("""[["msg",null,[null,null,"//host/i.js"],"hash","PROGRAM","glob"]]""")
        assertNull(challenge.script)
        assertEquals("//host/i.js", challenge.scriptUrl)
        assertEquals("glob", challenge.globalName)
    }

    @Test(expected = IllegalStateException::class)
    fun challengeWithoutAProgramIsRejected() {
        BotGuardCodec.parseChallenge("""[["msg",null,null,"hash"]]""")
    }

    @Test
    fun integrityResponseCarriesTokenAndTtl() {
        assertEquals("tok" to 43200L, BotGuardCodec.parseIntegrity("""["tok",43200,600,"fallback"]"""))
        assertEquals("tok" to BotGuardCodec.DEFAULT_TTL_SECONDS, BotGuardCodec.parseIntegrity("""["tok"]"""))
    }

    @Test
    fun visitorIdIsTheFirstProtobufField() {
        // 0x0A, length 11, then the ID, then an unrelated timestamp field.
        val id = "AbCdEfGhIjK"
        val blob = byteArrayOf(0x0A, 11) + id.toByteArray() + byteArrayOf(0x28, 0x01)
        val visitorData = Base64.getUrlEncoder().encodeToString(blob).replace("=", "%3D")
        assertEquals(id, BotGuardCodec.visitorId(visitorData))
    }

    @Test
    fun malformedVisitorDataYieldsNoId() {
        assertNull(BotGuardCodec.visitorId(null))
        assertNull(BotGuardCodec.visitorId(""))
        assertNull(BotGuardCodec.visitorId("not base64 !!"))
        assertNull(BotGuardCodec.visitorId(Base64.getUrlEncoder().encodeToString(byteArrayOf(0x12, 3, 65, 66, 67))))
    }
}
