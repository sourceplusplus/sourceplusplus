package spp.platform.core.auth.error

class AccessDenied : RuntimeException {

    private val reason: String

    constructor(reason: String) : this(reason, "Access denied: $reason")

    private constructor(reason: String, message: String) : super(message) {
        this.reason = reason
    }

    fun toEventBusException(): AccessDenied {
        return AccessDenied(
            reason, "EventBusException:AccessDenied[$reason]"
        )
    }
}
