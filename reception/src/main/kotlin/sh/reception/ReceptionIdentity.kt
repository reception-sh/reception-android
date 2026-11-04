package sh.reception

import sh.reception.client.DeviceInputValidation
import sh.reception.client.DeviceIdentity
import sh.reception.client.IdentityToken
import sh.reception.client.ReceptionAPIError
import sh.reception.client.UserIdentity
import sh.reception.client.storedCredentials

/** Identity, identity tokens and metadata behind the facade. Main thread only. */
internal object ReceptionIdentity {
    /** The token from `identifyWithToken`, in memory only. */
    var token: IdentityToken? = null
        private set

    /** The last value passed to `identifyWithToken`; passing it again changes nothing, whatever happened to it. */
    private var lastToken: String? = null

    fun identify(userId: String?, name: String?, email: String?) {
        val session = ReceptionState.session ?: return Log.error { "identify called before configure, ignored" }
        if (userId != null) {
            val verified = try {
                session.storedCredentials()?.verifiedUserId
            } catch (_: ReceptionAPIError) {
                null
            }
            if (verified != null && !UserIdentity.matches(verified, userId)) {
                Log.error { "identify(userId) does not match the verified user; call logout() when users switch" }
            }
        }
        session.store.pending = session.store.pending.copy(
            externalUserId = DeviceInputValidation.checked(userId, "userId", 255),
            name = DeviceInputValidation.checked(name, "name", 200),
            email = DeviceInputValidation.checked(email, "email", 254),
        )
        Log.debug { "Identity staged" }
    }

    fun identify(token: String) {
        val session = ReceptionState.session ?: return Log.error { "identifyWithToken called before configure, ignored" }
        if (token == lastToken) return Log.debug { "Identity token unchanged, ignored" }
        lastToken = token
        if (token.startsWith("ids_")) {
            return Log.error { "Identity token refused: this is the identity secret, which belongs only on your server" }
        }
        val identityToken = IdentityToken.parse(token)
            ?: return Log.error { "Identity token ignored, its user_id could not be read" }
        val verifiedUserId = try {
            session.storedCredentials()?.verifiedUserId
        } catch (_: ReceptionAPIError) {
            return Log.error { "Identity token ignored, secure storage unavailable" }
        }
        // A registration or anonymous-to-verified update may still be awaiting its verified ID.
        val sameUser = identityToken.matchesUser(verifiedUserId, session.store.pending.externalUserId, this.token?.userId)
        if (!sameUser) return switchUser(identityToken)
        // Sent with the next registration or device update (launch, foreground, chat opening, send), never at once:
        // a host that answers every rejection with a new token must not start a request loop.
        this.token = identityToken
        ReceptionState.chat?.clearVerificationRequirement()
        Log.debug { "Identity token staged" }
    }

    fun setMetadata(metadata: Map<String, String>) {
        val session = ReceptionState.session ?: return Log.error { "setMetadata called before configure, ignored" }
        session.store.pending = session.store.pending.copy(metadata = DeviceInputValidation.checked(metadata))
        Log.debug { "Metadata staged" }
    }

    /**
     * Another user's token: revoke and reset the previous session, keep only the new token and the push token.
     * The next send registers the new session.
     */
    fun switchUser(token: IdentityToken) {
        Log.info { "Signed-in user changed, previous session logged out" }
        val pending = ReceptionState.session?.store?.pending
        ReceptionAccount.logout()
        ReceptionState.session?.store?.pending = DeviceIdentity(pushToken = pending?.pushToken,
            pushEnvironment = pending?.pushEnvironment)
        this.token = token.copy(sent = false)
        lastToken = token.value
    }

    fun forget() {
        token = null
        lastToken = null
    }

    fun sent(token: IdentityToken) {
        if (this.token?.value == token.value) this.token = this.token?.copy(sent = true)
    }

    fun rejected(token: IdentityToken, error: ReceptionError) {
        if (this.token?.value == token.value) this.token = null
        ReceptionState.onEvent?.invoke(ReceptionEvent.IdentityRejected(error))
    }
}
