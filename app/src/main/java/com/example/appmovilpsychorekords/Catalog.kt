package com.example.appmovilpsychorekords

import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

/** Datos de muestra para revisar el front sin encender el backend de Angular. */
data class Record(
    val id: Int, val title: String, val artist: String, val genre: String,
    val year: Int, val format: String, val price: Int, val stock: Int,
    val cover: Int = 0, val condition: String = "Muy bueno"
)

object DemoCatalog {
    val records = listOf(
        Record(1, "Deseo carnal", "Alaska y Dinarama", "Pop", 1984, "Vinilo", 480, 4, R.drawable.deseo_carnal),
        Record(2, "Mouth to Mouth", "Lipps Inc.", "Disco", 1979, "Vinilo", 390, 3, R.drawable.mouth_to_mouth),
        Record(3, "Abbey Road", "The Beatles", "Rock", 1969, "Vinilo", 550, 10, condition = "Nuevo"),
        Record(4, "El silencio", "Caifanes", "Rock", 1992, "CD", 220, 6),
        Record(5, "Signos", "Soda Stereo", "Rock", 1986, "Cassette", 180, 2),
        Record(6, "Discovery", "Daft Punk", "Electrónica", 2001, "CD", 290, 0)
    )

    fun filter(query: String, format: String, favorites: Set<Int>? = null): List<Record> {
        val term = normalize(query.trim())
        return records.filter {
            (format == "Todos" || it.format == format) &&
                (favorites == null || it.id in favorites) &&
                normalize("${it.title} ${it.artist} ${it.genre}").contains(term)
        }
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "").lowercase(Locale.ROOT)
}

/** Reglas del carrito separadas de las vistas para poder probarlas. Precios en pesos enteros. */
class ShoppingCart {
    private val quantities = mutableMapOf<Int, Int>()
    val items: Map<Int, Int> get() = quantities.toMap()
    val count: Int get() = quantities.values.sum()
    val total: Int get() = DemoCatalog.records.sumOf { it.price * quantity(it.id) }

    fun quantity(id: Int) = quantities[id] ?: 0

    fun add(record: Record): Boolean {
        if (quantity(record.id) >= record.stock) return false
        quantities[record.id] = quantity(record.id) + 1
        return true
    }

    fun removeOne(id: Int) {
        val next = quantity(id) - 1
        if (next <= 0) quantities.remove(id) else quantities[id] = next
    }

    fun remove(id: Int) { quantities.remove(id) }

    fun restore(saved: Map<Int, Int>) {
        quantities.clear()
        DemoCatalog.records.forEach { record ->
            val quantity = (saved[record.id] ?: 0).coerceIn(0, record.stock)
            if (quantity > 0) quantities[record.id] = quantity
        }
    }
}

fun pesos(amount: Int): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"))
    .apply { maximumFractionDigits = 0 }.format(amount) + " MXN"
