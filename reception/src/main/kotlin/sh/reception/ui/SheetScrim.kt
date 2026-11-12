package sh.reception.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import sh.reception.ReceptionLocalization
import sh.reception.Theme
import sh.reception.sheetScrim
import sh.reception.strings.Strings

/**
 * The dimmed host app around the sheet. It fades with the sheet's position, so it follows a drag, and a tap on it
 * asks the sheet to close; accessibility services reach it after the sheet, as a "Close" button.
 */
@Composable
internal fun SheetScrim(state: SheetState) {
    val color = Theme.sheetScrim
    val label = ReceptionLocalization.string(Strings.close)
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(state) { detectTapGestures { state.requestClose() } }
            .semantics(mergeDescendants = true) {
                traversalIndex = 1f
                contentDescription = label
                onClick { state.requestClose() }
            },
    ) {
        drawRect(color, alpha = 1f - state.hidden)
    }
}
