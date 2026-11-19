package com.example.appmovilpsychorekords

import android.content.SharedPreferences
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

val CONDICIONES_PRODUCTO = listOf("Nuevo", "Usado - Excelente", "Usado - Bueno", "Usado - Regular")
val IVA_PORCENTAJE = BigDecimal("0.16")
val COSTO_ENVIO_ESTIMADO = BigDecimal("80.00")

private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
private val PHONE_REGEX = Regex("^\\d{10}$")
private val CP_REGEX = Regex("^\\d{5}$")

data class Record(
    val id: Int, val title: String, val artist: String, val genre: String,
    val year: Int?, val format: String, val price: BigDecimal, val stock: Int,
    val cover: Int = 0, val condition: String
)

fun List<Record>.filterCatalog(
    query: String,
    format: String,
    favorites: Set<Int>?,
    genre: String = "Todos",
    condition: String = "Todas",
    maxPrice: BigDecimal? = null,
    onlyAvailable: Boolean = false,
    sortBy: String = ""
): List<Record> {
    val term = normalize(query.trim())
    val filtered = filter { record ->
        val matchesText = term.isEmpty() ||
            normalize("${record.title} ${record.artist} ${record.genre} ${record.format}").contains(term)
        val matchesFormat = format == "Todos" || record.format.equals(format, ignoreCase = true)
        val matchesFavorites = favorites == null || record.id in favorites
        val matchesGenre = genre == "Todos" || record.genre.equals(genre, ignoreCase = true)
        val matchesCondition = condition == "Todas" || record.condition.equals(condition, ignoreCase = true)
        val matchesPrice = maxPrice == null || maxPrice <= BigDecimal.ZERO || record.price <= maxPrice
        val matchesStock = !onlyAvailable || record.stock > 0

        matchesText && matchesFormat && matchesFavorites && matchesGenre &&
            matchesCondition && matchesPrice && matchesStock
    }

    return when (sortBy) {
        "precio_asc" -> filtered.sortedBy { it.price }
        "precio_desc" -> filtered.sortedByDescending { it.price }
        "anio_desc" -> filtered.sortedByDescending { it.year ?: 0 }
        else -> filtered
    }
}

fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace("\\p{M}".toRegex(), "").lowercase(Locale.ROOT).trim()

class ShoppingCart {
    private val quantities = mutableMapOf<Int, Int>()
    val count: Int get() = quantities.values.sum()

    fun total(records: List<Record>): BigDecimal = records.sumOf {
        it.price * quantity(it.id).toBigDecimal()
    }

    fun iva(records: List<Record>): BigDecimal =
        (total(records) * IVA_PORCENTAJE).setScale(2, RoundingMode.HALF_UP)

    fun totalConEnvio(records: List<Record>, costoEnvio: BigDecimal): BigDecimal =
        (total(records) + iva(records) + costoEnvio).setScale(2, RoundingMode.HALF_UP)

    fun items(records: List<Record>): List<Pair<Record, Int>> =
        records.mapNotNull { record ->
            val qty = quantity(record.id)
            if (qty > 0) record to qty else null
        }

    fun quantity(id: Int) = quantities[id] ?: 0

    fun add(record: Record): Boolean {
        if (record.stock <= 0 || quantity(record.id) >= record.stock) return false
        quantities[record.id] = quantity(record.id) + 1
        return true
    }

    fun removeOne(id: Int) {
        val next = quantity(id) - 1
        if (next <= 0) quantities.remove(id) else quantities[id] = next
    }

    fun remove(id: Int) { quantities.remove(id) }

    fun clear() { quantities.clear() }

    fun loadFromPrefs(prefs: SharedPreferences) {
        if (quantities.isNotEmpty()) return
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("cart_") && value is Int && value > 0) {
                val id = key.removePrefix("cart_").toIntOrNull()
                if (id != null) quantities[id] = value
            }
        }
    }

    fun saveToPrefs(prefs: SharedPreferences) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cart_") }.forEach { editor.remove(it) }
        quantities.forEach { (id, qty) ->
            if (qty > 0) editor.putInt("cart_$id", qty)
        }
        editor.apply()
    }

    fun clampToStock(records: List<Record>) {
        records.forEach { record ->
            val current = quantities[record.id] ?: 0
            if (current > 0) {
                val clamped = current.coerceIn(0, record.stock)
                if (clamped <= 0) quantities.remove(record.id)
                else quantities[record.id] = clamped
            }
        }
    }

    fun restore(records: List<Record>, saved: (Int) -> Int) {
        val updated = mutableMapOf<Int, Int>()
        records.forEach { record ->
            val quantity = saved(record.id).coerceIn(0, record.stock)
            if (quantity > 0) updated[record.id] = quantity
        }
        quantities.clear()
        quantities.putAll(updated)
    }
}

data class ValidationErrors(val fieldErrors: Map<String, String>) {
    val isValid: Boolean get() = fieldErrors.isEmpty()
}

fun validateDireccionEnvio(
    destinatario: String,
    telefono: String,
    calle: String,
    numero: String,
    colonia: String,
    cp: String,
    ciudad: String,
    estado: String,
    referencias: String
): ValidationErrors {
    val errors = linkedMapOf<String, String>()
    if (destinatario.trim().isEmpty() || destinatario.trim().length > 150) {
        errors["destinatario"] = "Ingresa el nombre de quien recibe (máx. 150 caracteres)."
    }
    if (!PHONE_REGEX.matches(telefono.trim())) {
        errors["telefono"] = "El teléfono debe tener exactamente 10 dígitos numéricos."
    }
    if (calle.trim().isEmpty() || calle.trim().length > 100) {
        errors["calle"] = "Ingresa la calle (máx. 100 caracteres)."
    }
    if (numero.trim().isEmpty() || numero.trim().length > 20) {
        errors["numero"] = "Ingresa el número exterior/interior (máx. 20 caracteres)."
    }
    if (colonia.trim().isEmpty() || colonia.trim().length > 100) {
        errors["colonia"] = "Ingresa la colonia (máx. 100 caracteres)."
    }
    if (!CP_REGEX.matches(cp.trim())) {
        errors["cp"] = "El código postal debe tener exactamente 5 dígitos."
    }
    if (ciudad.trim().isEmpty() || ciudad.trim().length > 50) {
        errors["ciudad"] = "Ingresa la ciudad (máx. 50 caracteres)."
    }
    if (estado.trim().isEmpty() || estado.trim().length > 50) {
        errors["estado"] = "Ingresa el estado (máx. 50 caracteres)."
    }
    if (referencias.trim().length > 300) {
        errors["referencias"] = "Las referencias no pueden exceder 300 caracteres."
    }
    return ValidationErrors(errors)
}

fun validateContactoPunto(
    correo: String,
    telefono: String,
    referencias: String
): ValidationErrors {
    val errors = linkedMapOf<String, String>()
    val cleanEmail = correo.trim()
    if (cleanEmail.isEmpty() || cleanEmail.length > 150 || !EMAIL_REGEX.matches(cleanEmail)) {
        errors["correo"] = "Ingresa un correo electrónico válido (máx. 150 caracteres)."
    }
    if (!PHONE_REGEX.matches(telefono.trim())) {
        errors["telefono"] = "El teléfono debe tener exactamente 10 dígitos numéricos."
    }
    if (referencias.trim().isEmpty() || referencias.trim().length > 300) {
        errors["referencias"] = "Las referencias son obligatorias para punto medio (máx. 300 caracteres)."
    }
    return ValidationErrors(errors)
}

fun pesos(amount: BigDecimal): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).format(amount) + " MXN"
