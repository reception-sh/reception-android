package sh.reception.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.reception.client.NotificationAuthorization
import sh.reception.client.NotificationAuthorization.AUTHORIZED
import sh.reception.client.NotificationAuthorization.DENIED
import sh.reception.client.NotificationAuthorization.NOT_DETERMINED
import sh.reception.client.NotificationPermission
import sh.reception.configuration.PushPrompt

/** The slot under the person's latest message (iOS `PushPromptDecisionTests`) and Android's "not determined". */
class PushPromptDecisionTest {
    private fun resolve(
        prompt: PushPrompt? = PushPrompt.REPLIES,
        authorization: NotificationAuthorization? = NOT_DETERMINED,
        declined: Boolean = false,
        answered: Boolean = false,
        lastSender: String? = "USER",
        lastFailed: Boolean = false,
        canWrite: Boolean = true,
        pushActive: Boolean? = true,
        hidesStatus: Boolean = false,
    ) = PushPromptDecision.resolve(prompt, authorization, declined, answered, lastSender, lastFailed, canWrite, pushActive,
        hidesStatus)

    @Test
    fun theCardAsksWhileTheSystemHasNotAsked() {
        assertEquals(PushPromptSlot.Card(PushPrompt.REPLIES), resolve())
        assertEquals(PushPromptSlot.Card(PushPrompt.OFFERS), resolve(prompt = PushPrompt.OFFERS))
        assertEquals(PushPromptSlot.None, resolve(prompt = null))
        assertEquals(PushPromptSlot.None, resolve(declined = true))
        assertEquals(PushPromptSlot.None, resolve(answered = true))
        assertEquals(PushPromptSlot.None, resolve(authorization = DENIED))
        assertEquals(PushPromptSlot.None, resolve(authorization = null))
    }

    @Test
    fun theStatusLineNeedsAuthorizationAndAReachableDevice() {
        assertEquals(PushPromptSlot.Status, resolve(authorization = AUTHORIZED))
        assertEquals(PushPromptSlot.Status, resolve(prompt = null, authorization = AUTHORIZED))
        assertEquals(PushPromptSlot.None, resolve(authorization = AUTHORIZED, pushActive = false))
        assertEquals(PushPromptSlot.None, resolve(authorization = AUTHORIZED, pushActive = null))
        assertEquals(PushPromptSlot.None, resolve(authorization = AUTHORIZED, hidesStatus = true))
        // Hiding the status never hides the card.
        assertEquals(PushPromptSlot.Card(PushPrompt.REPLIES), resolve(hidesStatus = true))
    }

    @Test
    fun bothStepAsideUnlessThePersonWroteLastAndCanWrite() {
        for (authorization in listOf(NOT_DETERMINED, AUTHORIZED)) {
            assertEquals(PushPromptSlot.None, resolve(authorization = authorization, lastSender = "AGENT"))
            assertEquals(PushPromptSlot.None, resolve(authorization = authorization, lastSender = null))
            assertEquals(PushPromptSlot.None, resolve(authorization = authorization, lastFailed = true))
            assertEquals(PushPromptSlot.None, resolve(authorization = authorization, canWrite = false))
        }
    }

    @Test
    fun androidAsksOnlyOnThirteenAndLaterBeforeAnyAnswer() {
        fun read(enabled: Boolean = false, sdk: Int = 33, granted: Boolean = false, answered: Boolean = false,
                 rationale: Boolean = false, canRequest: Boolean = true) =
            NotificationPermission.resolve(enabled, sdk, granted, answered, rationale, canRequest)
        assertEquals(NOT_DETERMINED, read())
        assertEquals(AUTHORIZED, read(enabled = true, granted = true))
        assertEquals(AUTHORIZED, read(enabled = true, sdk = 26))
        assertEquals(DENIED, read(sdk = 32))
        assertEquals(DENIED, read(answered = true))
        assertEquals(DENIED, read(rationale = true))
        // Granted but switched off in the settings: only the settings can turn it on again.
        assertEquals(DENIED, read(granted = true))
        // An embedded chat without an activity result registry cannot ask.
        assertEquals(DENIED, read(canRequest = false))
    }
}
