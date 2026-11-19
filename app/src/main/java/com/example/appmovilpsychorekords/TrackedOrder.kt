package com.example.appmovilpsychorekords

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

val ORDER_STAGES = listOf("Pagado", "En preparación", "En tránsito", "Entregado")

private fun normalizedStatus(value: String) =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "").trim().lowercase(Locale.ROOT)

data class OrderLine(val title: String, val artist: String, val quantity: Int, val unitPrice: BigDecimal)

data class TrackedOrder(
    val id: Int,
    val date: String,
    val total: BigDecimal,
    val status: String,
    val carrier: String?,
    val trackingNumber: String?,
    val delivery: String,
    val lines: List<OrderLine>
) {
    val cancelled: Boolean get() = normalizedStatus(status) == "cancelado"
    val stage: Int get() = ORDER_STAGES.indexOfFirst { normalizedStatus(it) == normalizedStatus(status) }
}

private fun JSONObject.optionalText(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

fun parseOrders(data: JSONArray): List<TrackedOrder> = List(data.length()) { index ->
    val order = data.getJSONObject(index)
    val details = order.getJSONArray("detalles")
    val street = order.optionalText("envio_calle")
    val delivery = if (street != null) {
        listOfNotNull(
            order.optionalText("envio_destinatario"),
            listOfNotNull(street, order.optionalText("envio_numero")).joinToString(" "),
            order.optionalText("envio_colonia"),
            listOfNotNull(order.optionalText("envio_cp"), order.optionalText("envio_ciudad"),
                order.optionalText("envio_estado")).joinToString(", ")
        ).joinToString("\n")
    } else {
        listOfNotNull(order.optionalText("nombrepunto") ?: "Punto de entrega",
            order.optionalText("direccioncompleta"), order.optionalText("horarioatencion")).joinToString("\n")
    }
    TrackedOrder(
        id = order.getInt("idventa"),
        date = order.optString("fecha"),
        total = order.getString("totalventa").toBigDecimal(),
        status = order.optionalText("estadopedido") ?: "Sin estado",
        carrier = order.optionalText("paqueteria"),
        trackingNumber = order.optionalText("numero_guia"),
        delivery = delivery,
        lines = List(details.length()) { item ->
            val line = details.getJSONObject(item)
            OrderLine(line.optionalText("titulo") ?: "Producto", line.optionalText("artista").orEmpty(),
                line.getInt("cantidad"), line.getString("precioUnitario").toBigDecimal())
        }
    )
}
