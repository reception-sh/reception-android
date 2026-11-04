package sh.reception.receptionAndroidExample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.reception.Reception
import sh.reception.ReceptionPaywall
import sh.reception.ReceptionScreen

/** The paywalls and screens the example offers, and what the last card tap asked the app to show. */
object ExampleFeatures {
    /** A dialog the example shows for a paywall or screen a card opened; null when none is up. */
    var notice: String? by mutableStateOf(null)

    fun register() {
        Reception.paywalls = listOf(
            ReceptionPaywall(
                id = "pro_monthly",
                title = "Pro monthly",
                source = ReceptionPaywall.Source.App,
                products = listOf(ReceptionPaywall.Product("pro.monthly", price = "€4.99", period = "P1M", trial = "P1W")),
            ),
            ReceptionPaywall(id = "spring_sale", title = "Spring sale"),
        )
        Reception.onPaywall = { id -> notice = "Paywall $id" }
        Reception.screens = listOf(
            ReceptionScreen("settings") { notice = "Screen settings" },
            ReceptionScreen("order", value = "Order number") { number -> notice = "Screen order $number" },
        )
    }
}
