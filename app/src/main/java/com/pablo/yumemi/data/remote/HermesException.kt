package com.pablo.yumemi.data.remote

import com.pablo.yumemi.domain.ConnectionState
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val GENERIC_UNREACHABLE = "No puedo conectar con Hermes"

/** Specific Spanish explanation for a network failure; generic when the cause says nothing useful. */
internal fun networkMessage(cause: Throwable?): String = when (cause) {
    is UnknownHostException ->
        if (cause.message?.contains(".ts.net", ignoreCase = true) == true) {
            "No encuentro el servidor de Hermes. Comprueba que Tailscale esté conectado en el móvil (MagicDNS)."
        } else {
            "No encuentro el servidor de Hermes. Revisa la dirección y que Tailscale esté activo."
        }
    is ConnectException ->
        "Hermes no acepta conexiones en esa dirección. ¿Está encendido el API server y es el puerto correcto?"
    is SocketTimeoutException ->
        "Hermes tarda demasiado en responder. Revisa que Tailscale esté conectado."
    is SSLException ->
        "Error de seguridad (TLS) al conectar con Hermes. Si usas https, comprueba que Tailscale Serve esté activo y el certificado sea válido."
    else -> GENERIC_UNREACHABLE
}

/** Errors with Spanish, user-facing messages. */
sealed class HermesException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : HermesException("Configura la dirección de Hermes en Ajustes")
    class Unauthorized : HermesException("Clave de API incorrecta")
    class Unreachable(cause: Throwable? = null) : HermesException(networkMessage(cause), cause)
    class Http(val code: Int, val detail: String? = null) :
        HermesException("Hermes respondió con un error ($code)")
    class Protocol(detail: String) : HermesException("Respuesta inesperada de Hermes: $detail")
    /** The server has no Runs API (404/405 on POST /v1/runs): callers fall back to the session stream. */
    class RunsUnsupported : HermesException("Este Hermes no tiene la API de ejecuciones")
}

fun Throwable.toUserMessage(): String = when (this) {
    is HermesException -> message ?: "Error de Hermes"
    is IOException -> networkMessage(this)
    else -> "Algo salió mal: ${message ?: javaClass.simpleName}"
}

/** Null when the failure says nothing about connectivity. */
fun Throwable.toConnectionState(): ConnectionState? = when (this) {
    is HermesException.NotConfigured -> ConnectionState.NotConfigured
    is HermesException.Unauthorized -> ConnectionState.Unauthorized
    is HermesException.Unreachable -> ConnectionState.Unreachable(message ?: GENERIC_UNREACHABLE)
    is IOException -> ConnectionState.Unreachable(networkMessage(this))
    else -> null
}
