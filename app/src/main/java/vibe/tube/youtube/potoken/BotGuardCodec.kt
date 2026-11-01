package vibe.tube.youtube.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder
import java.util.Base64

/** What the Waa `Create` call hands back: the BotGuard program and the interpreter that runs it. */
data class BotGuardChallenge(
    val messageId: String?,
    /** The interpreter source when the response inlines it. */
    val script: String?,
    /** Where to fetch the interpreter when it is not inlined (scheme-relative, as served). */
    val scriptUrl: String?,
    val program: String,
    val globalName: String,
)

/** The pieces of the BotGuard exchange that are plain data handling, kept apart from the WebView that runs the VM. */
object BotGuardCodec {
    private val json = Json { isLenient = true }

    /** Decodes the base64 variants BotGuard emits: standard or URL-safe, with `.` standing in for padding. */
    fun decodeBase64(text: String): ByteArray {
        val normal = text.replace('+', '-').replace('/', '_').replace('.', '=').trimEnd('=')
        return Base64.getUrlDecoder().decode(normal)
    }

    /** A token as YouTube expects it: URL-safe alphabet, padding kept. */
    fun encodeToken(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_')

    /** The challenge payload is sent scrambled: base64, every byte shifted by 97, then UTF-8 JSON. */
    fun descramble(text: String): String {
        val bytes = decodeBase64(text)
        for (i in bytes.indices) bytes[i] = ((bytes[i].toInt() and 0xFF) + 97).toByte()
        return String(bytes, Charsets.UTF_8)
    }

    fun parseChallenge(response: String): BotGuardChallenge {
        val root = json.parseToJsonElement(response) as? JsonArray ?: error("challenge is not an array")
        val scrambled = (root.getOrNull(1) as? JsonPrimitive)?.contentOrNull
        val fields = if (root.size > 1 && scrambled != null) {
            json.parseToJsonElement(descramble(scrambled)) as? JsonArray ?: error("descrambled challenge is not an array")
        } else {
            root.getOrNull(0) as? JsonArray ?: error("challenge has no payload")
        }
        val program = fields.string(4) ?: error("challenge has no program")
        val globalName = fields.string(5) ?: error("challenge has no global name")
        return BotGuardChallenge(
            messageId = fields.string(0),
            script = (fields.getOrNull(1) as? JsonArray)?.firstString(),
            scriptUrl = (fields.getOrNull(2) as? JsonArray)?.firstString(),
            program = program,
            globalName = globalName,
        )
    }

    /** The integrity token and how long it stays valid, from the `GenerateIT` response. */
    fun parseIntegrity(response: String): Pair<String, Long> {
        val root = json.parseToJsonElement(response) as? JsonArray ?: error("integrity response is not an array")
        val token = root.string(0) ?: error("integrity response has no token")
        val ttlSeconds = (root.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: DEFAULT_TTL_SECONDS
        return token to ttlSeconds
    }

    /**
     * The 11-character visitor ID inside a `visitorData` blob. The stream host binds its token to
     * this ID, not to the whole blob, so a token minted over the blob is refused.
     *
     * The blob is URL-escaped base64 of a protobuf whose first field is the ID.
     */
    fun visitorId(visitorData: String?): String? {
        if (visitorData.isNullOrBlank()) return null
        val bytes = runCatching { decodeBase64(URLDecoder.decode(visitorData, "UTF-8")) }.getOrNull() ?: return null
        if (bytes.size < 2 || bytes[0].toInt() != 0x0A) return null
        val length = bytes[1].toInt() and 0xFF
        if (length == 0 || length > 0x7F || bytes.size < 2 + length) return null
        return String(bytes, 2, length, Charsets.US_ASCII).takeIf { id -> id.all { it.isLetterOrDigit() || it == '-' || it == '_' } }
    }

    const val DEFAULT_TTL_SECONDS = 6L * 60 * 60

    private fun JsonArray.string(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun JsonArray.firstString(): String? = firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && p.isString }?.content }}
