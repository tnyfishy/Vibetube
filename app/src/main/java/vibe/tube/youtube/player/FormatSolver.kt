package vibe.tube.youtube.player

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import vibe.tube.youtube.cipher.CipherDeobfuscator
import vibe.tube.youtube.cipher.PipePipeNsigDecoder

/**
 * Turns a /player answer whose formats are signed (`signatureCipher`) or carry a throttling `n`
 * into one with plain, playable URLs, so [StreamResolver.parse] stays a pure reader. Formats whose
 * signature cannot be solved are left as they came and counted as cipher-skipped by the parser.
 */
object FormatSolver {
    private val N_PARAM = Regex("[?&]n=")

    suspend fun solve(root: JsonObject): JsonObject {
        val streaming = root["streamingData"] as? JsonObject ?: return root
        val formats = streaming.list("formats")
        val adaptive = streaming.list("adaptiveFormats")
        val ciphers = (formats + adaptive).mapNotNull { f ->
            (f as? JsonObject)?.let { SignatureCipher.parse(it.text("signatureCipher") ?: it.text("cipher")) }
        }
        val solved = if (ciphers.isEmpty()) emptyMap() else solveSignatures(ciphers.map { it.signature })
        val signedFormats = formats.map { signed(it, solved) }
        val signedAdaptive = adaptive.map { signed(it, solved) }
        val urls = (signedFormats + signedAdaptive).mapNotNull { (it as? JsonObject)?.text("url") }
        if (urls.any { N_PARAM.containsMatchIn(it) }) PipePipeNsigDecoder.prefetch(urls.filter { N_PARAM.containsMatchIn(it) })
        val finalFormats = signedFormats.map { withN(it) }
        val finalAdaptive = signedAdaptive.map { withN(it) }
        val rebuilt = JsonObject(streaming + mapOf("formats" to JsonArray(finalFormats), "adaptiveFormats" to JsonArray(finalAdaptive)))
        return JsonObject(root + ("streamingData" to rebuilt))
    }

    private suspend fun solveSignatures(signatures: List<String>): Map<String, String> {
        val remote = PipePipeNsigDecoder.decodeSignatures(signatures)
        if (remote.keys.containsAll(signatures.distinct())) return remote
        val local = signatures.distinct().filter { it !in remote }.mapNotNull { s ->
            CipherDeobfuscator.deobfuscateSignature(s)?.let { s to it }
        }.toMap()
        return remote + local
    }

    private fun signed(format: JsonElement, solved: Map<String, String>): JsonElement {
        val obj = format as? JsonObject ?: return format
        if (obj.text("url") != null) return obj
        val cipher = SignatureCipher.parse(obj.text("signatureCipher") ?: obj.text("cipher")) ?: return obj
        val solvedSignature = solved[cipher.signature] ?: return obj
        return JsonObject(obj + ("url" to JsonPrimitive(cipher.signedUrl(solvedSignature))))
    }

    private suspend fun withN(format: JsonElement): JsonElement {
        val obj = format as? JsonObject ?: return format
        val url = obj.text("url") ?: return obj
        if (PipePipeNsigDecoder.rawN(url) == null) return obj
        val fixed = CipherDeobfuscator.transformNParamInUrl(url).takeIf { it != url }
            ?: PipePipeNsigDecoder.deobfuscateUrl(url)
            ?: return obj
        return JsonObject(obj + ("url" to JsonPrimitive(fixed)))
    }

    private fun JsonObject.list(key: String): List<JsonElement> = (get(key) as? JsonArray)?.toList().orEmpty()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
