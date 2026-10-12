package dev.jacedeb.jacesocial.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Mirrors server/lib/client.ts. Unknown fields are ignored, so the server can add more.

@Serializable
data class Activity(
    val type: String = "",
    val version: String? = null,
    val loader: String? = null,
    val server: String? = null,
    val world: String? = null,
    val modpack: String? = null,
    val started_at: String? = null,
)

@Serializable
data class Link(val label: String = "", val url: String = "")

@Serializable
data class Person(
    val uuid: String,
    val name: String = "",
    val online: Boolean = false,
    val status: String = "offline",
    val uses_jace: Boolean = false,
    val activity: Activity? = null,
    val last_seen: String? = null,
    val custom_status: String? = null,
    val status_emoji: String? = null,
    val minecraft_name: String? = null,
    val jace_name: String? = null,
    val avatar_url: String? = null,
    val bio: String? = null,
    val pronouns: String? = null,
    val accent_color: String? = null,
    val links: List<Link> = emptyList(),
    val unread: Int = 0,
    val role: String? = null,
    val banner_url: String? = null,
    val is_bot: Boolean = false,
    val bot_owner: String? = null,
    val nickname: String? = null,
    val roles: List<String> = emptyList(),
    val display_name: String? = null,
) {
    val shownName: String get() = nickname?.takeIf { it.isNotBlank() } ?: display_name?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class Realtime(val url: String = "", val key: String = "")

@Serializable
data class Me(
    val uuid: String,
    val name: String = "",
    val display_name: String? = null,
    val avatar_url: String? = null,
    val status: String = "online",
    val custom_status: String? = null,
    val status_emoji: String? = null,
    val minecraft_linked: Boolean = false,
    val jace_linked: Boolean = false,
    val inbox: String = "",
    val realtime: Realtime = Realtime(),
    val settings: JsonElement? = null,
    val onboarded: Boolean = true,
    val is_bot: Boolean = false,
)

@Serializable
data class FriendsData(
    val friends: List<Person> = emptyList(),
    val incoming: List<Person> = emptyList(),
    val outgoing: List<Person> = emptyList(),
)

/** What signing in gives: the session token and where live events come from. */
@Serializable
data class SignedIn(
    val token: String,
    val uuid: String = "",
    val name: String = "",
    val inbox: String = "",
    val realtime: Realtime = Realtime(),
)

@Serializable
data class JaceStart(val url: String, val state: String, val poll_key: String)

@Serializable
data class DeviceCode(
    val user_code: String,
    val verification_uri: String,
    val interval: Int = 5,
    val expires_at: String? = null,
    val ticket: String,
)

// --- chats

@Serializable
data class Attachment(val url: String = "", val name: String = "", val type: String = "", val size: Long = 0,
                      val width: Int? = null, val height: Int? = null)

@Serializable
data class Embed(val url: String = "", val title: String? = null, val description: String? = null, val image: String? = null,
                 val site: String? = null, val color: String? = null, val type: String = "link")

@Serializable
data class Reaction(val emoji: String, val count: Int = 1, val me: Boolean = false)

@Serializable
data class Message(
    val id: Long,
    val sender: String? = null,
    val body: String = "",
    val created_at: String = "",
    val edited_at: String? = null,
    val kind: String? = null,
    val read_at: String? = null,
    val recipient: String? = null,
    val channel_id: String? = null,
    val reply_to: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    val embeds: List<Embed> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val mentions: List<String> = emptyList(),
    val pinned_at: String? = null,
)

@Serializable
data class ReplyPreview(val id: Long, val sender: String? = null, val body: String = "", val attachments: Int = 0)

@Serializable
data class MessagesPage(
    val messages: List<Message> = emptyList(),
    val replies: Map<String, ReplyPreview> = emptyMap(),
    val people: Map<String, Person> = emptyMap(),
    val perms: Long? = null,
)

@Serializable
data class SentMessage(val message: Message)

@Serializable
data class Group(val id: String, val name: String = "", val icon_url: String? = null, val owner: String = "",
                 val members: List<Person> = emptyList(), val unread: Int = 0, val last_message_at: String? = null)

@Serializable
data class GroupsData(val groups: List<Group> = emptyList())

@Serializable
data class ServerItem(val id: String, val name: String = "", val icon_url: String? = null, val role: String = "", val unread: Int = 0)

@Serializable
data class ServersData(val servers: List<ServerItem> = emptyList())

/** Where messages go: a friend (direct messages) or a channel (group chat or server channel). */
sealed class ChatTarget {
    abstract val key: String
    data class Direct(val uuid: String) : ChatTarget() { override val key = "dm:$uuid" }
    data class Channel(val id: String) : ChatTarget() { override val key = "ch:$id" }
}
