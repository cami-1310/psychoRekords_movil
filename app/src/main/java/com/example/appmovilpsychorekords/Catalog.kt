package com.example.appmovilpsychorekords

import java.math.BigDecimal
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

data class Record(
    val id: Int, val title: String, val artist: String, val genre: String,
    val year: Int?, val format: String, val price: BigDecimal, val stock: Int,
    val cover: Int = 0, val condition: String
)

fun List<Record>.filterCatalog(query: String, format: String, favorites: Set<Int>?): List<Record> {
    val term = normalize(query.trim())
    return filter {
        (format == "Todos" || it.format == format) &&
            (favorites == null || it.id in favorites) &&
            normalize("${it.title} ${it.artist} ${it.genre}").contains(term)
    }
}

private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace("\\p{M}".toRegex(), "").lowercase(Locale.ROOT)

class ShoppingCart {
    private val quantities = mutableMapOf<Int, Int>()
    val count: Int get() = quantities.values.sum()

    fun total(records: List<Record>) = records.sumOf {
        it.price * quantity(it.id).toBigDecimal()
    }

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

    fun restore(records: List<Record>, saved: (Int) -> Int) {
        quantities.clear()
        records.forEach { record ->
            val quantity = saved(record.id).coerceIn(0, record.stock)
            if (quantity > 0) quantities[record.id] = quantity
        }
    }
}

fun pesos(amount: BigDecimal): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).format(amount) + " MXN"
