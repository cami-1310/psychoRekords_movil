package com.example.appmovilpsychorekords

import android.util.Base64
import androidx.lifecycle.ViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Session(val name: String, val email: String, val token: String) {
    val expired: Boolean
        get() = runCatching {
            val payload = Base64.decode(token.split('.')[1], Base64.URL_SAFE)
            JSONObject(String(payload, Charsets.UTF_8)).getLong("exp") <= System.currentTimeMillis() / 1000
        }.getOrDefault(true)
}

class StoreState : ViewModel() {
    val network = Executors.newSingleThreadExecutor()
    var session: Session? = null

    override fun onCleared() {
        network.shutdownNow()
    }
}

class ApiError(message: String) : IOException(message)

class PsychoApi(private val baseUrl: String) {
    fun products(): List<Record> {
        val data = JSONArray(request("productos"))
        return List(data.length()) { index ->
            val product = data.getJSONObject(index)
            val title = product.getString("titulo")
            val normalizedTitle = title.trim().lowercase()
            val cover = when {
                normalizedTitle.startsWith("deseo carnal") -> R.drawable.deseo_carnal
                normalizedTitle.startsWith("mouth to mouth") -> R.drawable.mouth_to_mouth
                else -> 0
            }
            Record(
                id = product.getInt("idprod"),
                title = title,
                artist = product.getString("artista"),
                genre = product.optString("generomus").takeUnless { product.isNull("generomus") }.orEmpty(),
                year = if (product.isNull("aniolanzam")) null else product.getInt("aniolanzam"),
                format = if (product.isNull("formato")) "Sin formato" else product.getString("formato"),
                price = product.getString("precio").toBigDecimal(),
                stock = product.optInt("cantstock").coerceAtLeast(0),
                cover = cover,
                condition = if (product.isNull("condiciones")) "Sin especificar" else product.getString("condiciones")
            )
        }
    }

    fun register(name: String, surname: String, email: String, password: String) {
        request("usuarios/registro", credentials(email, password)
            .put("nombre", name).put("apellidoP", surname))
    }

    fun login(email: String, password: String): Session {
        val result = JSONObject(request("usuarios/login", credentials(email, password)))
        val user = result.getJSONObject("usuario")
        return Session(user.getString("nombre"), user.getString("correoElectronico"), result.getString("token"))
    }

    fun logout(session: Session) {
        request("usuarios/logout", JSONObject(), session.token)
    }

    private fun credentials(email: String, password: String) =
        JSONObject().put("correoElectronico", email).put("password", password)

    private fun request(path: String, body: JSONObject? = null, token: String? = null): String {
        val connection = URL("${baseUrl.trimEnd('/')}/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status in 200..299) return connection.inputStream.bufferedReader().use { it.readText() }
            val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val message = if (status in 400..499) {
                runCatching { JSONObject(response).getString("message") }.getOrNull()
            } else null
            throw ApiError(message ?: "La API respondió con un error ($status).")
        } finally {
            connection.disconnect()
        }
    }
}
