package com.example.appmovilpsychorekords

import android.util.Base64
import androidx.lifecycle.ViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Session(val name: String, val email: String, val token: String) {
    val expired: Boolean
        get() = runCatching {
            val payload = Base64.decode(token.split('.')[1], Base64.URL_SAFE)
            JSONObject(String(payload, Charsets.UTF_8)).getLong("exp") <= System.currentTimeMillis() / 1000
        }.getOrDefault(true)

    val userId: Int
        get() = runCatching {
            val payload = Base64.decode(token.split('.')[1], Base64.URL_SAFE)
            val json = JSONObject(String(payload, Charsets.UTF_8))
            json.optInt("idUsuario", json.optInt("idusuario", 0))
        }.getOrDefault(0)
}

class StoreState : ViewModel() {
    val network = Executors.newSingleThreadExecutor()
    var session: Session? = null
    val cart = ShoppingCart()
    var records: List<Record> = emptyList()
    var cartLoaded = false

    override fun onCleared() {
        network.shutdownNow()
    }
}

class ApiError(message: String, val statusCode: Int = 0) : IOException(message) {
    val isUnauthorized: Boolean get() = statusCode == 401 || statusCode == 403
}

data class PuntoEntrega(
    val idpunto: Int,
    val nombrepunto: String,
    val direccioncompleta: String,
    val horarioatencion: String
)

data class TarifaEnvio(
    val idtarifa: Int,
    val paqueteria: String,
    val servicio: String,
    val precio: BigDecimal,
    val diasMin: Int,
    val diasMax: Int
)

val DEFAULT_TARIFAS_ENVIO = listOf(
    TarifaEnvio(1, "Correos de México", "Estándar Registrado", BigDecimal("95.00"), 5, 10),
    TarifaEnvio(2, "Estafeta", "Terrestre Nacional", BigDecimal("165.00"), 3, 5),
    TarifaEnvio(3, "DHL", "Express Nacional", BigDecimal("216.00"), 1, 3)
)

val DEFAULT_PUNTOS_ENTREGA = listOf(
    PuntoEntrega(1, "Plaza de la Patria (Centro)", "Exedra de la Plaza Principal, Zona Centro, Aguascalientes", "Lun a Sáb 12:00 – 19:00"),
    PuntoEntrega(2, "Jardín de San Marcos", "Balaustrada Norte frente al Templo, Barrio de San Marcos, Ags.", "Lun a Dom 13:00 – 20:00"),
    PuntoEntrega(3, "Velaria Mall / Expoplaza", "Entrada principal sobre Av. López Mateos, Aguascalientes", "Vie a Dom 15:00 – 20:00")
)

data class DireccionEnvio(
    val destinatario: String,
    val telefono: String,
    val calle: String,
    val numero: String,
    val colonia: String,
    val cp: String,
    val ciudad: String,
    val estado: String,
    val referencias: String = ""
)

data class ContactoPunto(
    val correo: String,
    val telefono: String,
    val referencias: String
)

data class PerfilEnvio(
    val destinatario: String,
    val correo: String,
    val telefono: String,
    val calle: String,
    val numero: String,
    val colonia: String,
    val cp: String,
    val ciudad: String,
    val estado: String
)

data class VentaConfirmada(
    val idVenta: Int,
    val totalVenta: BigDecimal
)

class PsychoApi(private val baseUrl: String) {
    fun orders(session: Session): List<TrackedOrder> =
        parseOrders(JSONArray(request("ventas/mis-pedidos", token = session.token)))

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

    fun changePassword(session: Session, current: String, replacement: String): String {
        val body = JSONObject().put("passwordActual", current).put("passwordNueva", replacement)
        val response = request("usuarios/perfil/password", body, session.token, "PATCH")
        return JSONObject(response).optString("message", "Contraseña actualizada.")
    }

    fun tarifasEnvio(session: Session? = null): List<TarifaEnvio> = runCatching {
        val data = JSONArray(request("tarifas-envio", token = session?.token))
        List(data.length()) { index ->
            val item = data.getJSONObject(index)
            TarifaEnvio(
                idtarifa = item.optInt("idtarifa", index + 1),
                paqueteria = item.optString("paqueteria", "Paquetería"),
                servicio = item.optString("servicio", "Estándar"),
                precio = item.optString("precio", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                diasMin = item.optInt("dias_min", 2),
                diasMax = item.optInt("dias_max", 5)
            )
        }.ifEmpty { DEFAULT_TARIFAS_ENVIO }
    }.getOrDefault(DEFAULT_TARIFAS_ENVIO)

    fun puntosEntrega(session: Session? = null): List<PuntoEntrega> = runCatching {
        val data = JSONArray(request("puntos-entrega", token = session?.token))
        List(data.length()) { index ->
            val item = data.getJSONObject(index)
            PuntoEntrega(
                idpunto = item.optInt("idpunto", index + 1),
                nombrepunto = item.optString("nombrepunto", "Punto medio"),
                direccioncompleta = item.optString("direccioncompleta").takeUnless { item.isNull("direccioncompleta") }.orEmpty(),
                horarioatencion = item.optString("horarioatencion").takeUnless { item.isNull("horarioatencion") }.orEmpty()
            )
        }.ifEmpty { DEFAULT_PUNTOS_ENTREGA }
    }.getOrDefault(DEFAULT_PUNTOS_ENTREGA)

    fun perfilEnvio(session: Session): PerfilEnvio {
        val p = JSONObject(request("usuarios/perfil", token = session.token))
        fun clean(key: String) = if (p.isNull(key)) "" else p.optString(key, "").trim()
        val nombreCompleto = listOf(clean("nombre"), clean("apellidoP"), clean("apellidoM"))
            .filter { it.isNotEmpty() }.joinToString(" ")
        return PerfilEnvio(
            destinatario = nombreCompleto,
            correo = clean("correoElectronico"),
            telefono = clean("telefono").replace(Regex("\\D"), ""),
            calle = clean("calle"),
            numero = clean("numCasa"),
            colonia = clean("colonia"),
            cp = clean("CP").replace(Regex("\\D"), ""),
            ciudad = clean("ciudad"),
            estado = clean("estado")
        )
    }

    fun crearVentaDomicilio(
        session: Session,
        idTarifa: Int,
        items: List<Pair<Record, Int>>,
        direccion: DireccionEnvio,
        expectedTotal: BigDecimal
    ): VentaConfirmada {
        val envioJson = JSONObject()
            .put("destinatario", direccion.destinatario.trim())
            .put("telefono", direccion.telefono.trim())
            .put("calle", direccion.calle.trim())
            .put("numero", direccion.numero.trim())
            .put("colonia", direccion.colonia.trim())
            .put("cp", direccion.cp.trim())
            .put("ciudad", direccion.ciudad.trim())
            .put("estado", direccion.estado.trim())
            .apply {
                if (direccion.referencias.trim().isNotEmpty()) {
                    put("referencias", direccion.referencias.trim())
                }
            }
        val body = JSONObject()
            .put("idusuario", session.userId)
            .put("idUsuario", session.userId)
            .put("idtarifa", idTarifa)
            .put("productos", buildProductosArray(items))
            .put("envio", envioJson)

        val responseText = runCatching { request("ventas", body, session.token) }.getOrElse { err ->
            if (err is ApiError && err.isUnauthorized) throw err
            val fallbackBody = JSONObject()
                .put("idusuario", session.userId)
                .put("idUsuario", session.userId)
                .put("idpunto", 1)
                .put("idPunto", 1)
                .put("productos", buildProductosArray(items))
                .put("envio", JSONObject()
                    .put("correo", session.email)
                    .put("telefono", direccion.telefono.trim())
                    .put("referencias", "${direccion.calle} ${direccion.numero}, Col. ${direccion.colonia}, CP ${direccion.cp}, ${direccion.ciudad}, ${direccion.estado}".take(300))
                )
            request("ventas", fallbackBody, session.token)
        }
        return parseVentaConfirmada(JSONObject(responseText), expectedTotal)
    }

    fun crearVentaPunto(
        session: Session,
        idPunto: Int,
        items: List<Pair<Record, Int>>,
        contacto: ContactoPunto,
        expectedTotal: BigDecimal
    ): VentaConfirmada {
        val envioJson = JSONObject()
            .put("correo", contacto.correo.trim())
            .put("telefono", contacto.telefono.trim())
            .put("referencias", contacto.referencias.trim())
        val body = JSONObject()
            .put("idusuario", session.userId)
            .put("idUsuario", session.userId)
            .put("idpunto", idPunto)
            .put("idPunto", idPunto)
            .put("productos", buildProductosArray(items))
            .put("envio", envioJson)

        val response = JSONObject(request("ventas", body, session.token))
        return parseVentaConfirmada(response, expectedTotal)
    }

    private fun buildProductosArray(items: List<Pair<Record, Int>>): JSONArray {
        val arr = JSONArray()
        items.forEach { (record, qty) ->
            arr.put(
                JSONObject()
                    .put("idprod", record.id)
                    .put("idProd", record.id)
                    .put("cantidad", qty)
                    .put("precioUnitario", record.price.toDouble())
            )
        }
        return arr
    }

    private fun parseVentaConfirmada(response: JSONObject, fallbackTotal: BigDecimal): VentaConfirmada {
        val idVenta = response.optInt("idVenta", response.optInt("idventa", 0))
        val totalStr = response.optString("totalVenta", response.optString("totalventa", ""))
        val total = totalStr.toBigDecimalOrNull() ?: fallbackTotal
        return VentaConfirmada(idVenta = idVenta, totalVenta = total)
    }

    private fun credentials(email: String, password: String) =
        JSONObject().put("correoElectronico", email).put("password", password)

    private fun request(
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        method: String = if (body == null) "GET" else "POST"
    ): String {
        val connection = URL("${baseUrl.trimEnd('/')}/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = method
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status in 200..299) return connection.inputStream.bufferedReader().use { it.readText() }
            val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val message = if (status in 400..599) {
                runCatching {
                    val obj = JSONObject(response)
                    val errDetail = obj.optString("error").takeIf { it.isNotEmpty() }
                    val msg = obj.optString("message").takeIf { it.isNotEmpty() }
                    if (msg != null && errDetail != null) "$msg ($errDetail)" else msg ?: errDetail
                }.getOrNull()
            } else null
            throw ApiError(message ?: "La API respondió con un error ($status).", status)
        } finally {
            connection.disconnect()
        }
    }
}
