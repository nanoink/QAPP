package com.qapp.app.domain

/**
 * Valida acesso conforme regras de negocio do app de seguranca.
 * Nao acessa Android/Supabase diretamente; recebe os dados necessarios.
 */
class AccessValidator {

    fun validate(userId: String?, driver: Driver?): AccessResult {
        if (userId.isNullOrBlank()) {
            return AccessResult.Blocked(AccessBlockReason.NOT_AUTHENTICATED)
        }
        if (driver == null) {
            return AccessResult.Blocked(AccessBlockReason.DRIVER_NOT_FOUND)
        }
        if (!driver.active) {
            return AccessResult.Blocked(AccessBlockReason.DRIVER_INACTIVE)
        }
        if (!driver.subscriptionStatus.equals("active", ignoreCase = true)) {
            return AccessResult.Blocked(AccessBlockReason.SUBSCRIPTION_INACTIVE)
        }
        return AccessResult.Allowed
    }
}

data class Driver(
    val id: String,
    val active: Boolean,
    val subscriptionStatus: String
)

sealed class AccessResult {
    object Allowed : AccessResult()
    data class Blocked(val reason: AccessBlockReason) : AccessResult()
}

enum class AccessBlockReason {
    NOT_AUTHENTICATED,
    DRIVER_NOT_FOUND,
    SUBSCRIPTION_INACTIVE,
    DRIVER_INACTIVE
}
