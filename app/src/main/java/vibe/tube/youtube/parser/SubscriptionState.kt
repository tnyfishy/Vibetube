package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement

internal fun subscriptionState(root: JsonElement): Boolean? =
    WatchParser.entityPayloads(root).firstNotNullOfOrNull { it.at("subscriptionStateEntity").at("subscribed").bool }
        ?: root.firstObject("subscriptionStateEntity").at("subscribed").bool
        ?: root.firstObject("subscribeButtonRenderer").at("subscribed").bool
        ?: root.firstObject("subscribeButtonViewModel").at("subscribed").bool
