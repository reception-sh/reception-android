package sh.reception.viewmodel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import sh.reception.ReceptionUnread
import sh.reception.client.ApplicationLifecycle
import sh.reception.client.ReceptionAPIError
import sh.reception.client.RetryScope
import sh.reception.client.RetryState
import sh.reception.client.hasConversation
import sh.reception.client.hasStartedChat
import sh.reception.client.isRegistered
import sh.reception.client.pushActive
import sh.reception.client.update
import sh.reception.model.ConversationResponse
import sh.reception.model.MessagesResponse

/**
 * The full conversation GET, at opening, foreground and after a rejected GET. With [recheckLimits] (opening and
 * foreground only) it may ask `?limits=1` and then resumes messages a released limit held.
 */
internal suspend fun ChatModel.load(recheckLimits: Boolean = false) {
    restored()
    val session = session ?: return
    if (fetching || !current || !session.store.hasStartedChat || session.halt != null ||
        session.retries["load"]?.stopped == true
    ) return
    if (!session.isRegistered) {
        if (recheckLimits) resumeTransientFailures()
        return
    }
    val permissionRevision = sendingPermissionRevision
    val cachedIds = messages.filter { it.seq != null }.map { it.id }.toSet()
    fetching = true
    isLoading = true
    try {
        session.retries["load"]?.delay?.let { delay(it) }
        session.update()
        if (!current || !currentCoroutineContext().isActive || !ApplicationLifecycle.isActive) return
        val generation = ReceptionUnread.readGeneration
        val checked = if (recheckLimits && isWatched) session.cooldowns.beginRecheck() else emptyMap()
        val path = if (checked.isEmpty()) "conversation" else "conversation?limits=1"
        val response = session.api.request<ConversationResponse>(path, authenticated = true)
        if (!current || !currentCoroutineContext().isActive) return
        if (generation != ReceptionUnread.readGeneration) return reloadAfterStaleGet()
        session.store.hasConversation = response.conversation != null
        response.push?.let { session.store.pushActive = it }
        response.device?.let { updateSendingPermission(it, permissionRevision) }
        val incomingSequences = response.messages.mapNotNull { it.seq }.toSet()
        messages = messages.filterNot { message ->
            message.id in cachedIds && message.seq?.let { it !in incomingSequences } == true
        }
        replaceTeam((response.team ?: emptyList()).reversed().associateBy { it.id })
        merge(response.messages)
        if (response.messages.isEmpty()) saveMessages()
        if (recheckLimits && isWatched) {
            if (checked.isNotEmpty()) {
                session.cooldowns.applyRecheck(response.limits, checked)
                cooldownRevision += 1
            }
            resumeLimitHeldMessages()
        }
        resumeTransientFailures()
        markRead(processedReadTarget(response.conversation?.id, response.conversation != null, response.boundedRead,
            response.messages))
        session.retries.remove("load")
    } catch (error: ReceptionAPIError) {
        if (currentCoroutineContext().isActive && session.halt == null) {
            session.retries.getOrPut("load") { RetryState() }.failed(error)
        }
        report(error)
        if (recheckLimits && (isRecoverableSubmissionFailure(error) || (error as? ReceptionAPIError)?.isCooldown == true)) {
            resumeTransientFailures()
        }
    } finally {
        fetching = false
        isLoading = false
    }
}

/** Incremental GETs after the cursor, following `hasMore`; each page is merged and read like a single poll. */
internal suspend fun ChatModel.poll() {
    restored()
    val session = session ?: return
    if (!current || !isChatActive || !currentCoroutineContext().isActive || !ApplicationLifecycle.isActive ||
        !session.store.hasStartedChat || session.halt != null || session.retries["poll"]?.stopped == true || fetching
    ) return
    // A request wait must not hold the stream reader; cooldownsChanged polls once it ends.
    if (session.cooldowns.blocking(listOf(RetryScope.REQUESTS)) != null) {
        pollDeferred = true
        return
    }
    fetching = true
    try {
        session.retries["poll"]?.delay?.let { delay(it) }
        var hasMore = true
        while (hasMore) {
            if (!stillPolling()) return
            val after = maxSeq
            val generation = ReceptionUnread.readGeneration
            val permissionRevision = sendingPermissionRevision
            val response = session.api.request<MessagesResponse>("conversation/messages?after=$after", authenticated = true)
            if (!stillPolling()) return
            if (generation != ReceptionUnread.readGeneration) return reloadAfterStaleGet()
            response.device?.let { updateSendingPermission(it, permissionRevision) }
            replaceTeam(team + (response.team ?: emptyList()).associateBy { it.id })
            merge(response.messages)
            markRead(processedReadTarget(response.conversation?.id, response.conversation != null, response.boundedRead,
                response.messages))
            hasMore = response.hasMore == true
            // A page that does not advance the cursor would repeat forever.
            if (hasMore && maxSeq <= after) throw ReceptionAPIError("invalid_response", 200)
        }
        session.retries.remove("poll")
    } catch (error: ReceptionAPIError) {
        if (currentCoroutineContext().isActive && session.halt == null) {
            session.retries.getOrPut("poll") { RetryState() }.failed(error)
        }
        report(error)
    } finally {
        fetching = false
    }
}

private suspend fun ChatModel.stillPolling(): Boolean =
    current && isChatActive && currentCoroutineContext().isActive && ApplicationLifecycle.isActive
