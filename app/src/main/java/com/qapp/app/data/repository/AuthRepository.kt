package com.qapp.app.data.repository

import android.util.Log
import com.qapp.app.core.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class AuthRepository {

    private val client = SupabaseClientProvider.client
    private val sessionState = MutableStateFlow(client.auth.currentSessionOrNull() != null)

    fun observeSession(): StateFlow<Boolean> = sessionState.asStateFlow()

    suspend fun signIn(email: String, password: String): Result<Unit> {
        val masked = maskEmail(email)
        Log.d("QAPP_AUTH", "signIn started for email=$masked")
        return try {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val sessionPresent = client.auth.currentSessionOrNull() != null
            sessionState.value = sessionPresent
            val userId = client.auth.currentUserOrNull()?.id
            Log.d("QAPP_AUTH", "signIn success, userId=$userId")
            Log.d("QAPP_AUTH", "session present = $sessionPresent")
            if (!sessionPresent) {
                Result.failure(AuthUserMessageException("Falha ao criar sessao", null, null))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            sessionState.value = false
            val statusCode = extractStatusCode(e)
            Log.e(
                "QAPP_AUTH",
                "signIn failed type=${e::class.simpleName}, message=${e.message}, statusCode=$statusCode",
                e
            )
            val userMessage = mapUserMessage(e, statusCode)
            Result.failure(
                AuthUserMessageException(
                    userMessage = userMessage,
                    statusCode = statusCode,
                    debugMessage = e.message
                )
            )
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            client.auth.signOut()
            sessionState.value = false
            Result.success(Unit)
        } catch (e: Exception) {
            val statusCode = extractStatusCode(e)
            Log.e(
                "QAPP_AUTH",
                "signOut failed type=${e::class.simpleName}, message=${e.message}, statusCode=$statusCode",
                e
            )
            Result.failure(
                AuthUserMessageException(
                    userMessage = "Falha ao sair",
                    statusCode = statusCode,
                    debugMessage = e.message
                )
            )
        }
    }

    fun isAuthenticated(): Boolean {
        return client.auth.currentSessionOrNull() != null
    }

    fun currentDriverId(): String? {
        return client.auth.currentUserOrNull()?.id
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        val masked = maskEmail(email)
        return try {
            client.auth.resetPasswordForEmail(email)
            Log.d("QAPP_AUTH", "resetPassword requested for email=$masked")
            Result.success(Unit)
        } catch (e: Exception) {
            val statusCode = extractStatusCode(e)
            Log.e(
                "QAPP_AUTH",
                "resetPassword failed type=${e::class.simpleName}, message=${e.message}, statusCode=$statusCode",
                e
            )
            Result.failure(
                AuthUserMessageException(
                    userMessage = "Se este email existir, voce recebera instrucoes.",
                    statusCode = statusCode,
                    debugMessage = e.message
                )
            )
        }
    }

    suspend fun updatePassword(password: String): Result<Unit> {
        return try {
            client.auth.updateUser {
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val statusCode = extractStatusCode(e)
            Log.e(
                "QAPP_AUTH",
                "updatePassword failed type=${e::class.simpleName}, message=${e.message}, statusCode=$statusCode",
                e
            )
            Result.failure(
                AuthUserMessageException(
                    userMessage = "Falha ao atualizar senha",
                    statusCode = statusCode,
                    debugMessage = e.message
                )
            )
        }
    }

    private fun mapUserMessage(e: Exception, statusCode: Int?): String {
        val message = extractErrorMessage(e).lowercase()
        return when {
            message.contains("confirm") && message.contains("email") ->
                "Confirme seu email para continuar"
            message.contains("invalid login credentials") ->
                "Email ou senha invalidos"
            message.contains("invalid") || message.contains("wrong") || message.contains("password") ->
                "Email ou senha invalidos"
            message.contains("provider") && message.contains("disabled") ->
                "Provedor Email/Senha esta desativado no Supabase"
            e is IOException || message.contains("network") || message.contains("timeout") || message.contains("connect") ->
                "Sem conexao"
            statusCode != null && statusCode >= 500 ->
                "Erro no servidor (codigo $statusCode)"
            statusCode != null && (statusCode == 400 || statusCode == 401) ->
                "Email ou senha invalidos"
            message.isNotBlank() -> message
            else -> "Falha no login"
        }
    }

    private fun extractErrorMessage(e: Exception): String {
        val methods = e.javaClass.methods
        val candidates = listOf("getError", "getDescription", "getErrorDescription", "getMessage")
        for (name in candidates) {
            try {
                val method = methods.firstOrNull { it.name == name }
                val value = method?.invoke(e)
                if (value is String && value.isNotBlank()) {
                    return value
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        return e.message.orEmpty()
    }

    private fun extractStatusCode(e: Exception): Int? {
        return try {
            val method = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" }
            val value = method?.invoke(e)
            if (value is Int) value else null
        } catch (_: Exception) {
            null
        }
    }

    private fun maskEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return "***"
        val name = parts[0]
        val domain = parts[1]
        val maskedName = when {
            name.length <= 2 -> "***"
            else -> "${name.first()}***${name.last()}"
        }
        return "$maskedName@$domain"
    }
}

class AuthUserMessageException(
    val userMessage: String,
    val statusCode: Int?,
    val debugMessage: String?
) : Exception(userMessage)
