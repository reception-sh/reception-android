package sh.reception.client

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// The chat's stored state. A chat reset (`DeviceStore.resetChat`) forgets every value in [chatKeys]; drafts and link
// taps of a discarded chat are dropped once the store stops accepting them.

/** The values a chat reset forgets. */
internal val chatKeys = listOf(
    "cursor", "draftText", "hasConversation", "hasStartedChat", "conversationStatus", "lastMessageAt",
    "pushActive", "unreadPolling", "pendingActionClicks", "actionClickOutcomes",
)

private val stringMap = MapSerializer(String.serializer(), String.serializer())

internal var DeviceStore.chatRevision: Int
    get() = values.int("chatRevision")
    set(value) = values.putInt("chatRevision", value)

internal var DeviceStore.hasStartedChat: Boolean
    get() = values.boolean("hasStartedChat") || hasConversation
    set(value) = values.putBoolean("hasStartedChat", value)

internal var DeviceStore.hasConversation: Boolean
    get() = values.boolean("hasConversation")
    set(value) = values.putBoolean("hasConversation", value)

internal var DeviceStore.conversationStatus: String?
    get() = values.string("conversationStatus")
    set(value) = values.putString("conversationStatus", value)

/** Epoch milliseconds of the latest message the service reported. */
internal var DeviceStore.lastMessageAt: Long?
    get() = values.long("lastMessageAt")
    set(value) = values.putLong("lastMessageAt", value)

internal var DeviceStore.pushActive: Boolean?
    get() = values.optionalBoolean("pushActive")
    set(value) = values.putOptionalBoolean("pushActive", value)

internal var DeviceStore.unreadPolling: List<UnreadStage>?
    get() = values.decoded("unreadPolling", ListSerializer(UnreadStage.serializer()))
    set(value) = values.encoded("unreadPolling", ListSerializer(UnreadStage.serializer()), value)

internal var DeviceStore.pendingActionClicks: List<String>
    get() = values.decoded("pendingActionClicks", ListSerializer(String.serializer())) ?: emptyList()
    set(value) {
        if (acceptsDrafts) values.encoded("pendingActionClicks", ListSerializer(String.serializer()), value)
    }

/** Link tap results by message ID, beside the queue so its stored format stays a plain list. */
internal var DeviceStore.actionClickOutcomes: Map<String, String>
    get() = values.decoded("actionClickOutcomes", stringMap) ?: emptyMap()
    set(value) {
        if (acceptsDrafts) values.encoded("actionClickOutcomes", stringMap, value)
    }

internal var DeviceStore.draftText: String
    get() = values.string("draftText") ?: ""
    set(value) {
        if (acceptsDrafts) values.putString("draftText", value)
    }

internal var DeviceStore.cursor: Long
    get() = values.string("cursor")?.toLongOrNull() ?: 0
    set(value) = values.putString("cursor", value.toString())
