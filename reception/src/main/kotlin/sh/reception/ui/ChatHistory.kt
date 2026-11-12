package sh.reception.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import sh.reception.Theme
import sh.reception.appearance
import sh.reception.foreground
import sh.reception.viewmodel.ChatModel
import sh.reception.viewmodel.ChatScrollState
import sh.reception.viewmodel.PushPromptSlot
import sh.reception.viewmodel.continuePushPrompt
import sh.reception.viewmodel.declinePushPrompt
import sh.reception.viewmodel.pushPromptSlot
import sh.reception.viewmodel.composerCooldown
import sh.reception.viewmodel.messageLayouts

private val noTeam = MutableStateFlow(emptyMap<String, Nothing>())

/**
 * The messages, newest at the bottom, above the connection banner, cooldown notice and composer. The list is laid
 * out from the bottom, so the newest message stays in place while the keyboard moves; new messages scroll into view
 * only while the reader is at the newest one, and the reader's own always do. A reader further up gets the
 * jump-to-latest control for replies that arrived below.
 */
@Composable
internal fun ChatHistory(model: ChatModel, topInset: Dp, requestNotifications: (() -> Unit)?) {
    val listState = rememberLazyListState()
    val scrollState = remember(model) { ChatScrollState() }
    var scrollRevision by remember { mutableIntStateOf(0) }
    // Saved by id, so the photo stays open through a rotation like on iOS; its zoom starts over.
    var lightboxId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val messages = model.messages
    val appearance = Theme.appearance
    val members by (model.teamProfiles?.members ?: noTeam).collectAsState()
    val photos by (model.teamProfiles?.images ?: noTeam).collectAsState()
    val layouts = remember(messages, appearance, model.team, members, photos) { model.messageLayouts(appearance) }
    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(messages.map { it.id }) {
        if (scrollState.messagesChanged(messages.map { ChatScrollState.Entry(it.id, it.sender) })) scrollRevision += 1
    }
    // Without a way to ask, the card never shows; the status line still does.
    val slot = model.pushPromptSlot.takeUnless { it is PushPromptSlot.Card && requestNotifications == null }
        ?: PushPromptSlot.None
    LaunchedEffect(model.isTyping, slot) { if (scrollState.followsNewest) scrollRevision += 1 }
    LaunchedEffect(scrollRevision) {
        if (scrollRevision == 0 || messages.isEmpty()) return@LaunchedEffect
        // Once now, and once more after the new rows have settled their height.
        listState.animateScrollToItem(0)
        delay(Theme.LAYOUT_SETTLE_MILLIS)
        listState.animateScrollToItem(0)
    }
    LaunchedEffect(newestId) {
        val id = newestId ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == id } }
            .collect { scrollState.newest(id, it) }
    }
    ReplyFeedback(model)
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val rowWidth = maxWidth - Theme.spacing * 2
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(start = Theme.spacing, end = Theme.spacing, top = topInset + Theme.small,
                    bottom = Theme.composerMessageGap),
                modifier = Modifier.fillMaxSize().fadingTop(topInset).nestedScroll(keyboardDismissal()),
            ) {
                if (model.isTyping) {
                    item(key = TYPING_KEY) { TypingIndicator(Modifier.padding(top = Theme.groupSpacing).animateItem()) }
                }
                if (slot != PushPromptSlot.None) {
                    item(key = PUSH_PROMPT_KEY) {
                        PushPromptSlotView(slot, decline = model::declinePushPrompt,
                            accept = { requestNotifications?.let(model::continuePushPrompt) },
                            modifier = Modifier.padding(top = Theme.groupSpacing).animateItem())
                    }
                }
                itemsIndexed(messages.asReversed(), key = { _, message -> message.id }) { reversedIndex, message ->
                    val index = messages.lastIndex - reversedIndex
                    MessageRow(
                        message = message,
                        layout = layouts.getOrNull(index) ?: return@itemsIndexed,
                        width = rowWidth,
                        actions = model.actions(message) { lightboxId = it.id },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (messages.isEmpty() && (model.isRestored || model.session == null)) EmptyState()
            JumpToLatest(scrollState.showsJumpControl, scrollState.unseenCount, Modifier.align(Alignment.BottomCenter)) {
                scrollState.jumpedToNewest()
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
        // Starting to write shows the newest message, like Messages.
        BottomBar(model, onFocus = { scrollRevision += 1 })
    }
    val lightbox = lightboxId?.let { id -> messages.firstNotNullOfOrNull { message -> message.attachments.find { it.id == id } } }
    lightbox?.let { ImageLightbox(it) { lightboxId = null } }
}

private const val TYPING_KEY = "support-typing"
private const val PUSH_PROMPT_KEY = "support-push-prompt"

/** The connection banner, the cooldown notice and the composer, above the part of the keyboard or navigation bar that covers the chat. */
@Composable
private fun BottomBar(model: ChatModel, onFocus: () -> Unit) {
    val insets = chatInsets()
    val padding = insets.ime.union(insets.navigationBars).only(WindowInsetsSides.Bottom).asPaddingValues()
    Column(Modifier.fillMaxWidth().padding(padding)) {
        ConnectionBanner(model.connection, Modifier.padding(horizontal = Theme.spacing))
        val cooldown = model.composerCooldown
        if (cooldown != null && !model.sendingDisabled && !model.verificationRequired) {
            CooldownNotice(cooldown, Modifier.padding(start = Theme.spacing, end = Theme.spacing, bottom = Theme.small))
        }
        Composer(model, onFocus)
    }
}

/** Messages fade out under the title bar instead of meeting it at a hard edge. */
@Composable
private fun Modifier.fadingTop(topInset: Dp): Modifier {
    val opaque = Theme.foreground
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val start = (topInset - Theme.headerFadeOverlap).toPx()
        drawRect(Brush.verticalGradient(listOf(opaque.copy(alpha = 0f), opaque), startY = start,
            endY = start + Theme.headerFadeHeight.toPx()), blendMode = BlendMode.DstIn)
    }
}

/** A drag over the messages puts the keyboard away, as iOS does with interactive keyboard dismissal. */
@Composable
private fun keyboardDismissal(): NestedScrollConnection {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    return remember(keyboard, focus, view) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && view.keyboardShown()) {
                    focus.clearFocus()
                    keyboard?.hide()
                }
                return Offset.Zero
            }
        }
    }
}
