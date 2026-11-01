package vibe.tube.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vibe.tube.youtube.model.NotifyLevel
import javax.inject.Inject
import javax.inject.Singleton

/** Last known subscription state of one channel: what the subscribe button shows. */
data class ChannelSubscription(
    val subscribed: Boolean,
    val notifyLevel: NotifyLevel? = null,
    val notifyParams: Map<NotifyLevel, String> = emptyMap(),
)

/**
 * Subscription state shared by every screen with a subscribe button, keyed by (account scope, channel).
 * Channel pages, subscribe actions and search's featured channel write to it, so a button can show the
 * known state at once instead of waiting for a fresh request.
 */
@Singleton
class ChannelSubscriptionStore @Inject constructor() {
    private val mutable = MutableStateFlow<Map<Pair<String, String>, ChannelSubscription>>(emptyMap())
    val states: StateFlow<Map<Pair<String, String>, ChannelSubscription>> = mutable.asStateFlow()

    fun get(scope: String, channelId: String): ChannelSubscription? = mutable.value[scope to channelId]

    fun put(scope: String, channelId: String, state: ChannelSubscription) {
        mutable.update { it + ((scope to channelId) to state) }
    }

    fun update(scope: String, channelId: String, transform: (ChannelSubscription) -> ChannelSubscription) {
        val current = get(scope, channelId) ?: return
        put(scope, channelId, transform(current))
    }

    fun setSubscribed(scope: String, channelId: String, subscribed: Boolean) {
        val previous = get(scope, channelId)
        put(
            scope,
            channelId,
            ChannelSubscription(
                subscribed = subscribed,
                notifyLevel = if (subscribed) previous?.notifyLevel else null,
                notifyParams = previous?.notifyParams.orEmpty(),
            ),
        )
    }
}
