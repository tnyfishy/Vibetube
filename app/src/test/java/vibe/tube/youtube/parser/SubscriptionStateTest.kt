package vibe.tube.youtube.parser

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SubscriptionStateTest {
    @Test fun modernSubscribedEntityWinsOverLegacyButton() {
        val root = Json.parseToJsonElement("""{"state":{"subscriptionStateEntity":{"subscribed":true}},"subscribeButtonRenderer":{"subscribed":false}}""")
        assertEquals(true, subscriptionState(root))
    }
    @Test fun legacyFalseAndUnknownRemainDistinct() {
        assertEquals(false, subscriptionState(Json.parseToJsonElement("""{"subscribeButtonRenderer":{"subscribed":false}}""")))
        assertNull(subscriptionState(Json.parseToJsonElement("{}")))
    }
}
