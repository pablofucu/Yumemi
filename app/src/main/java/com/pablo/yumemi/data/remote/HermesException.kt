package com.pablo.yumemi.data.remote

import com.pablo.yumemi.domain.ConnectionState
import java.io.IOException

/** Errors with Spanish, user-facing messages. */
sealed class HermesException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : HermesException("Configura la dirección de Hermes en Ajustes")
    class Unauthorized : HermesException("Clave de API incorrecta")
    class Unreachable(cause: Throwable? = null) : HermesException("No puedo conectar con Hermes", cause)
    class Http(val code: Int, val detail: String? = null) :
        HermesException("Hermes respondió con un error ($code)")
    class Protocol(detail: String) : HermesException("Respuesta inesperada de Hermes: $detail")
}

fun Throwable.toUserMessage(): String = when (this) {
    is HermesException -> message ?: "Error de Hermes"
    is IOException -> "No puedo conectar con Hermes"
    else -> "Algo salió mal: ${message ?: javaClass.simpleName}"
}

/** Null when the failure says nothing about connectivity. */
fun Throwable.toConnectionState(): ConnectionState? = when (this) {
    is HermesException.NotConfigured -> ConnectionState.NotConfigured
    is HermesException.Unauthorized -> ConnectionState.Unauthorized
    is HermesException.Unreachable -> ConnectionState.Unreachable(message ?: "No puedo conectar con Hermes")
    is IOException -> ConnectionState.Unreachable("No puedo conectar con Hermes")
    else -> null
}
