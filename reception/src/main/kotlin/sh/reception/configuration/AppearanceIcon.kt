package sh.reception.configuration

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.painter.Painter

/** An icon choice: a symbol by its SF Symbols name, an uploaded image, an image from the host app, or nothing. */
internal sealed interface AppearanceIcon {
    data class Symbol(val name: String) : AppearanceIcon

    /** An uploaded dashboard image, already turned into its URL on this service. */
    data class Remote(val url: String) : AppearanceIcon

    class Local(val painter: Painter) : AppearanceIcon

    /** A drawable of the host app, vector or bitmap. */
    data class LocalResource(@DrawableRes val id: Int) : AppearanceIcon

    data object Hidden : AppearanceIcon
}
