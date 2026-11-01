package vibe.tube.youtube.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which InnerTube clients the stream host has recently refused, across videos. A refusal
 * arrives on a URL that has already been discarded, so without this memory every new video walks
 * the client ladder from the same refused client and stalls the same way.
 *
 * Entries lapse on a timer: a gate describes the current network and visitor, not the client, and
 * must not outlive either.
 */
class ClientGateRegistry(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val gatedUntil = ConcurrentHashMap<String, Long>()
    private val refusalStrikes = ConcurrentHashMap<String, Int>()

    /** Called with the client name whenever a client is demoted. */
    @Volatile var onGated: (String) -> Unit = {}

    fun reportGated(client: String?) {
        val key = key(client) ?: return
        gatedUntil[key] = clockMs() + ttlMs
        onGated(key)
    }

    /**
     * The host took the client's token and still refused it. One refusal can be a cold attestation the next
     * mint fixes, so the client is demoted on the second strike.
     *
     * @return true when this strike demoted the client.
     */
    fun reportRefused(client: String?): Boolean {
        val key = key(client) ?: return false
        val strikes = refusalStrikes.merge(key, 1, Int::plus) ?: 1
        if (strikes < REFUSALS_BEFORE_DEMOTION) return false
        refusalStrikes.remove(key)
        gatedUntil[key] = clockMs() + ttlMs
        onGated(key)
        return true
    }

    /** Records what a refused googlevideo [url] says about the client that minted it. */
    fun reportDenied(url: String?): StreamDenialKind {
        val kind = StreamDenialClassifier.kind(url, clockMs() / 1000)
        val client = StreamDenialClassifier.clientOf(url)
        when (kind) {
            StreamDenialKind.AttestationGated -> reportGated(client)
            StreamDenialKind.TokenRejected -> reportRefused(client)
            StreamDenialKind.UrlExpired, StreamDenialKind.Unknown -> Unit
        }
        return kind
    }

    fun isGated(client: String?): Boolean {
        val key = key(client) ?: return false
        val until = gatedUntil[key] ?: return false
        if (clockMs() < until) return true
        gatedUntil.remove(key, until)
        return false
    }

    fun gatedClients(): Set<String> {
        val now = clockMs()
        gatedUntil.entries.removeAll { it.value <= now }
        return gatedUntil.keys.toSet()
    }

    fun clear() {
        gatedUntil.clear()
        refusalStrikes.clear()
    }

    /** Gated clients move to the back of the ladder instead of dropping out: a stale gate must never leave nothing to try. */
    fun <T> demoted(items: List<T>, nameOf: (T) -> String): List<T> {
        val (gated, open) = items.partition { isGated(nameOf(it)) }
        return open + gated
    }

    private fun key(client: String?): String? = client?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

    companion object {
        const val DEFAULT_TTL_MS = 30L * 60 * 1000
        private const val REFUSALS_BEFORE_DEMOTION = 2
    }
}
