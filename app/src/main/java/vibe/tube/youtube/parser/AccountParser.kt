package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import vibe.tube.account.AccountIdentity
import vibe.tube.youtube.model.AccountProfile

object AccountParser {

    fun isLoggedOut(root: JsonElement): Boolean? {
        val flag = root.path("responseContext", "mainAppWebResponseContext", "loggedOut").bool
        if (flag != null) return flag
        var result: Boolean? = null
        root.path("responseContext", "serviceTrackingParams").list().forEach { service ->
            service.at("params").list().forEach { p ->
                if (p.at("key").str == "logged_in") {
                    when (p.at("value").str) {
                        "0" -> result = true
                        "1" -> if (result == null) result = false
                    }
                }
            }
        }
        return result
    }

    fun visitorData(root: JsonElement): String? = root.path("responseContext", "visitorData").str

    fun parseMenu(root: JsonElement): AccountProfile {
        if (isLoggedOut(root) == true) return AccountProfile("", null, null, false)
        val header = root.firstObject("activeAccountHeaderRenderer")
            ?: return AccountProfile("", null, null, false)
        val name = header.at("accountName").textOf().orEmpty()
        if (name.isBlank()) return AccountProfile("", null, null, false)
        return AccountProfile(
            name = name,
            handle = header.at("channelHandle").textOf(),
            avatarUrl = ItemParsers.thumbs(header.at("accountPhoto")).lastOrNull()?.url,
            loggedIn = true,
        )
    }

    fun parseIdentities(root: JsonElement): List<AccountIdentity> {
        val identities = ArrayList<AccountIdentity>()
        root.walk { key, value ->
            if (key == "accountItem") {
                val name = value.at("accountName").textOf().orEmpty()
                val pageId = value.firstString("pageId")
                val selected = value.at("isSelected").bool == true
                val handle = value.at("channelHandle").textOf()
                if (name.isNotBlank()) {
                    identities += AccountIdentity(
                        key = pageId ?: if (selected && identities.none { it.pageId == null }) "primary" else "identity-${identities.size}",
                        pageId = pageId,
                        name = name,
                        handle = handle,
                        channelId = value.firstString("channelId") ?: value.firstString("browseId"),
                        avatarUrl = ItemParsers.thumbs(value.at("accountPhoto")).lastOrNull()?.url,
                    )
                }
                false
            } else true
        }
        return identities
    }
}
