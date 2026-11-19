package com.example.appmovilpsychorekords

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.math.BigDecimal
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val green = Color.rgb(60, 234, 95)
    private val dark = Color.rgb(3, 26, 22)
    private val surface = Color.rgb(16, 43, 37)
    private val muted = Color.rgb(179, 198, 188)
    private val white = Color.rgb(245, 250, 244)
    private val danger = Color.rgb(255, 110, 110)
    private val regularFont by lazy { ResourcesCompat.getFont(this, R.font.nunito_family)!! }
    private val emphasisFont by lazy { Typeface.create(regularFont, 600, false) }
    private val favorites = mutableSetOf<Int>()
    private val prefs by lazy { getSharedPreferences("psycho_catalog", MODE_PRIVATE) }
    private val storeState by viewModels<StoreState>()
    private val api by lazy { PsychoApi(getString(R.string.psycho_api_url)) }

    private val cart: ShoppingCart get() = storeState.cart
    private var records: List<Record>
        get() = storeState.records
        set(value) { storeState.records = value }

    private var loadingCatalog = true
    private var catalogError: String? = null
    private lateinit var container: FrameLayout
    private lateinit var nav: BottomNavigationView
    private var tab = 1
    private var detailId = 0

    // Filtros del catálogo / inventario (sincronizados con la web)
    private var query = ""
    private var format = "Todos"
    private var genreFilter = "Todos"
    private var conditionFilter = "Todas"
    private var maxPriceText = ""
    private var onlyAvailable = false
    private var sortBy = ""
    private var onlyFavorites = false

    // Estado de Mis Pedidos (OrderTrackingScreen de Chris)
    private var ordersOpen = false

    // Estado de Checkout y Pagos
    private var checkoutOpen = false
    private var loadingCheckoutOptions = false
    private var tarifas = DEFAULT_TARIFAS_ENVIO
    private var puntos = DEFAULT_PUNTOS_ENTREGA
    private var selectedEntrega = "" // "tarifa-<id>" o "punto-<id>"
    private var destNombre = ""
    private var destTelefono = ""
    private var destCalle = ""
    private var destNumero = ""
    private var destColonia = ""
    private var destCp = ""
    private var destCiudad = ""
    private var destEstado = ""
    private var destReferencias = ""
    private var contactoCorreo = ""
    private var contactoTelefono = ""
    private var contactoReferencias = ""
    private var checkoutFieldErrors = emptyMap<String, String>()
    private var checkoutGeneralError: String? = null
    private var pagoPaypalRealizado = false
    private var paypalOrderId: String? = null
    private var submittingPurchase = false
    private var ventaConfirmada: VentaConfirmada? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.main)
        container = findViewById(R.id.screen_container)
        nav = findViewById(R.id.bottom_navigation)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val inSubScreen = detailId != 0 || checkoutOpen || ordersOpen
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            nav.visibility = if (imeVisible || inSubScreen) View.GONE else View.VISIBLE
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (!storeState.cartLoaded) {
            cart.loadFromPrefs(prefs)
            storeState.cartLoaded = true
        }
        favorites.addAll(prefs.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() })
        tab = savedInstanceState?.getInt("tab", 1) ?: 1
        detailId = savedInstanceState?.getInt("detail", 0) ?: 0
        query = savedInstanceState?.getString("query").orEmpty()
        format = savedInstanceState?.getString("format") ?: "Todos"
        genreFilter = savedInstanceState?.getString("genreFilter") ?: "Todos"
        conditionFilter = savedInstanceState?.getString("conditionFilter") ?: "Todas"
        maxPriceText = savedInstanceState?.getString("maxPriceText").orEmpty()
        onlyAvailable = savedInstanceState?.getBoolean("onlyAvailable", false) ?: false
        sortBy = savedInstanceState?.getString("sortBy").orEmpty()
        onlyFavorites = savedInstanceState?.getBoolean("favorites", false) ?: false
        ordersOpen = savedInstanceState?.getBoolean("orders", false) ?: false

        listOf("Inicio", "Catálogo", "Carrito", "Cuenta").forEachIndexed { index, title ->
            nav.menu.add(0, index + 1, index, title).setIcon(
                listOf(R.drawable.ic_home, R.drawable.ic_records, R.drawable.ic_cart, R.drawable.ic_person)[index]
            )
        }
        val tint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(green, muted))
        nav.itemIconTintList = tint
        nav.itemTextColor = tint
        nav.itemTextAppearanceActive = R.style.TextAppearance_Psycho_Navigation
        nav.itemTextAppearanceInactive = R.style.TextAppearance_Psycho_Navigation
        nav.labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
        nav.itemActiveIndicatorColor = ColorStateList.valueOf(Color.rgb(27, 70, 43))
        nav.selectedItemId = tab
        nav.setOnItemSelectedListener { item ->
            ordersOpen = false
            checkoutOpen = false
            tab = item.itemId
            detailId = 0
            render()
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ordersOpen -> { ordersOpen = false; render() }
                    detailId != 0 -> { detailId = 0; render() }
                    checkoutOpen -> {
                        if (!submittingPurchase) {
                            checkoutOpen = false
                            render()
                        }
                    }
                    tab != 1 -> go(1)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        if (records.isNotEmpty()) {
            loadingCatalog = false
            render()
        } else {
            render()
            loadCatalog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("detail", detailId)
        outState.putString("query", query)
        outState.putString("format", format)
        outState.putString("genreFilter", genreFilter)
        outState.putString("conditionFilter", conditionFilter)
        outState.putString("maxPriceText", maxPriceText)
        outState.putBoolean("onlyAvailable", onlyAvailable)
        outState.putString("sortBy", sortBy)
        outState.putBoolean("favorites", onlyFavorites)
        outState.putBoolean("orders", ordersOpen)
    }

    private fun persist() {
        cart.saveToPrefs(prefs)
        prefs.edit().putStringSet("favorites", favorites.map { it.toString() }.toSet()).apply()
        updateBadge()
    }

    private fun updateBadge() {
        if (cart.count == 0) nav.removeBadge(3)
        else nav.getOrCreateBadge(3).apply {
            number = cart.count; backgroundColor = green; badgeTextColor = dark
        }
    }

    private fun go(destination: Int) {
        detailId = 0
        checkoutOpen = false
        ordersOpen = false
        tab = destination
        nav.selectedItemId = destination
        render()
    }

    private fun render() {
        container.removeAllViews()
        updateBadge()
        val inSubScreen = detailId != 0 || checkoutOpen || ordersOpen
        val imeVisible = ViewCompat.getRootWindowInsets(container)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        nav.visibility = if (imeVisible || inSubScreen) View.GONE else View.VISIBLE

        if (ordersOpen) {
            val session = storeState.session?.takeUnless { it.expired }
            if (session != null) {
                container.addView(OrderTrackingScreen(
                    this, this, api, session, storeState.network,
                    isCurrentSession = { storeState.session?.token == session.token },
                    onExpired = {
                        storeState.session = null
                        ordersOpen = false
                        render()
                        auth(false)
                    },
                    onBack = { ordersOpen = false; render() }
                ))
                return
            }
            ordersOpen = false
            nav.visibility = if (imeVisible) View.GONE else View.VISIBLE
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isSmoothScrollingEnabled = true
        }
        val page = column().apply { setPadding(dp(20), dp(12), dp(20), dp(36)) }
        scroll.addView(page)
        container.addView(scroll)
        val record = records.find { it.id == detailId }
        when {
            record != null -> showDetail(page, record)
            checkoutOpen -> showCheckout(page)
            else -> when (tab) {
                1 -> showHome(page)
                2 -> showCatalog(page)
                3 -> showCart(page)
                4 -> showAccount(page)
            }
        }
    }

    private fun brand(page: LinearLayout) {
        val row = row()
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_rana); contentDescription = "Logo de PsychoRekords"
            background = rounded(Color.WHITE, 16)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val title = column().apply { setPadding(dp(10), 0, 0, 0) }
        title.addView(text("PsychoRekords", 21, white, true))
        title.addView(text("El sonido se siente.", 12, green))
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row)
        space(page, 20)
    }

    private fun showHome(page: LinearLayout) {
        brand(page)
        val hero = FrameLayout(this).apply { background = rounded(surface); clipToOutline = true }
        hero.addView(ImageView(this).apply {
            setImageResource(R.drawable.banner_inicio); scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33031A16, 0xF5031A16.toInt())
            )
        }, FrameLayout.LayoutParams(-1, -1))
        val copy = column().apply { setPadding(dp(22), dp(24), dp(22), dp(20)) }
        copy.addView(text("PARA QUIENES ESCUCHAN DIFERENTE", 10, green, true).apply { letterSpacing = .03f })
        space(copy, 18)
        copy.addView(text("Tu música.\nEn tus manos.", 34, white, true))
        space(copy, 10)
        copy.addView(text("Vinilos, CDs y cassettes.\nEncuentra tu próxima pieza favorita.", 15, white))
        space(copy, 18)
        copy.addView(button("Explorar catálogo  →") { go(2) })
        hero.addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        page.addView(hero, LinearLayout.LayoutParams(-1, dp(350)))
        space(page, 18)
        page.addView(text("VINILOS  /  CDs  /  CASSETTES", 11, green, true).apply { letterSpacing = .05f })
        space(page, 22)
        val heading = row()
        heading.addView(text("En la selección", 23, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(link("Ver todo") { go(2) })
        page.addView(heading)
        space(page, 12)
        if (!catalogStatus(page)) productGrid(page, records.take(2))
        space(page, 20)
        val note = panel()
        note.addView(text("Mucho más que música", 20, white, true))
        space(note, 8)
        note.addView(text("Rescatamos la calidez de los formatos clásicos y la cultura de coleccionar.", 14, muted))
        note.addView(link("Conoce PsychoRekords  →") { about() })
        page.addView(note)
    }

    private fun showCatalog(page: LinearLayout) {
        page.addView(text("ENCUENTRA TU SONIDO", 11, green, true))
        space(page, 6)
        val header = row()
        header.addView(text("Catálogo", 32, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(link("Actualizar stock") { loadCatalog() })
        page.addView(header)
        space(page, 12)
        if (catalogStatus(page)) return

        val search = input("Buscar título, artista, género o formato", query)
        page.addView(search.first)
        space(page, 6)

        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filters = row()
        horizontal.addView(filters)
        page.addView(horizontal)
        listOf("Todos", "Vinilo", "CD", "Cassette").forEach { label ->
            filters.addView(button(label, format != label) {
                query = search.second.text.toString()
                format = label
                render()
            }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        }
        space(page, 8)

        val generos = listOf("Todos") + records.map { it.genre.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val condiciones = listOf("Todas") + CONDICIONES_PRODUCTO
        val ordenes = listOf(
            "" to "Orden: Predeterminado",
            "precio_asc" to "Precio: menor a mayor",
            "precio_desc" to "Precio: mayor a menor",
            "anio_desc" to "Año: más reciente"
        )
        val ordenActualTexto = ordenes.firstOrNull { it.first == sortBy }?.second ?: "Orden: Predeterminado"

        val filterRow1 = row()
        filterRow1.addView(button("Género: $genreFilter", genreFilter == "Todos") {
            query = search.second.text.toString()
            MaterialAlertDialogBuilder(this)
                .setTitle("Filtrar por género")
                .setItems(generos.toTypedArray()) { _, which ->
                    genreFilter = generos[which]
                    render()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })

        filterRow1.addView(button("Condición: $conditionFilter", conditionFilter == "Todas") {
            query = search.second.text.toString()
            MaterialAlertDialogBuilder(this)
                .setTitle("Filtrar por condición")
                .setItems(condiciones.toTypedArray()) { _, which ->
                    conditionFilter = condiciones[which]
                    render()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(filterRow1)
        space(page, 6)

        val filterRow2 = row()
        filterRow2.addView(button(ordenActualTexto, sortBy.isEmpty()) {
            query = search.second.text.toString()
            MaterialAlertDialogBuilder(this)
                .setTitle("Ordenar catálogo")
                .setItems(ordenes.map { it.second }.toTypedArray()) { _, which ->
                    sortBy = ordenes[which].first
                    render()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })

        val precioLabel = if (maxPriceText.isBlank()) "Precio máx: Todos" else "Máx: $$maxPriceText"
        filterRow2.addView(button(precioLabel, maxPriceText.isBlank()) {
            query = search.second.text.toString()
            promptMaxPrice()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(filterRow2)
        space(page, 6)

        val availableToggle = MaterialSwitch(this).apply {
            typeface = regularFont
            text = "Solo productos con stock disponible"
            setTextColor(muted)
            isChecked = onlyAvailable
            minHeight = dp(44)
        }
        page.addView(availableToggle)

        val favoriteToggle = MaterialSwitch(this).apply {
            typeface = regularFont
            text = "Solo mis favoritos"
            setTextColor(muted)
            isChecked = onlyFavorites
            minHeight = dp(44)
        }
        page.addView(favoriteToggle)

        val statusRow = row()
        val results = text("", 12, muted)
        statusRow.addView(results, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(link("Limpiar filtros") {
            resetCatalogFilters()
            render()
        })
        page.addView(statusRow)
        space(page, 12)

        val list = column()
        page.addView(list)

        fun refresh() {
            list.removeAllViews()
            val maxPrice = maxPriceText.toBigDecimalOrNull()
            val filtered = records.filterCatalog(
                query = query,
                format = format,
                favorites = if (onlyFavorites) favorites else null,
                genre = genreFilter,
                condition = conditionFilter,
                maxPrice = maxPrice,
                onlyAvailable = onlyAvailable,
                sortBy = sortBy
            )
            results.text = "${filtered.size} de ${records.size} piezas · Inventario sincronizado"
            if (filtered.isEmpty()) {
                empty(
                    list,
                    "Sin coincidencias",
                    "Prueba otro artista, formato, condición o limpia los filtros activos.",
                    "Limpiar filtros"
                ) {
                    resetCatalogFilters()
                    render()
                }
            } else {
                productGrid(list, filtered)
            }
        }

        search.second.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s.toString()
                refresh()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        availableToggle.setOnCheckedChangeListener { _, checked -> onlyAvailable = checked; refresh() }
        favoriteToggle.setOnCheckedChangeListener { _, checked -> onlyFavorites = checked; refresh() }
        refresh()
    }

    private fun resetCatalogFilters() {
        query = ""
        format = "Todos"
        genreFilter = "Todos"
        conditionFilter = "Todas"
        maxPriceText = ""
        onlyAvailable = false
        sortBy = ""
        onlyFavorites = false
    }

    private fun promptMaxPrice() {
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val priceInput = input("Precio máximo en MXN (vacío para todos)", maxPriceText)
        priceInput.second.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        box.addView(priceInput.first)
        MaterialAlertDialogBuilder(this)
            .setTitle("Tope de precio")
            .setView(box)
            .setPositiveButton("Aplicar") { _, _ ->
                val raw = priceInput.second.text.toString().trim()
                val num = raw.toBigDecimalOrNull()
                maxPriceText = if (num != null && num > BigDecimal.ZERO) num.toPlainString() else ""
                render()
            }
            .setNeutralButton("Quitar tope") { _, _ ->
                maxPriceText = ""
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun productGrid(parent: LinearLayout, records: List<Record>) {
        val columns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        records.chunked(columns).forEach { group ->
            val line = row().apply { gravity = Gravity.TOP }
            group.forEach { record ->
                line.addView(productCard(record), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(6); marginStart = dp(6); bottomMargin = dp(12)
                })
            }
            repeat(columns - group.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            parent.addView(line)
        }
    }

    private fun productCard(record: Record): LinearLayout {
        val card = column().apply { background = rounded(surface); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val art = cover(record)
        card.addView(art, LinearLayout.LayoutParams(-1, dp(if (resources.configuration.screenWidthDp >= 600) 200 else 140)))
        art.setOnClickListener { openDetail(record) }
        space(card, 10)
        card.addView(text(listOfNotNull(record.format.uppercase(), record.year?.toString()).joinToString(" · "), 10, green, true))
        card.addView(text(record.title, 16, white, true).apply { minLines = 2; setOnClickListener { openDetail(record) } })
        card.addView(text(record.artist, 12, muted).apply { minLines = 2 })
        val stockText = if (record.stock > 0) "Disponibles: ${record.stock}" else "Stock agotado"
        val stockColor = if (record.stock > 0) muted else danger
        card.addView(text(stockText, 11, stockColor))
        space(card, 4)
        card.addView(text(pesos(record.price), 16, white, true))
        val inCart = cart.quantity(record.id)
        val canAdd = record.stock > 0 && inCart < record.stock
        val btnLabel = when {
            record.stock <= 0 -> "Agotado"
            inCart >= record.stock -> "En carrito ($inCart/${record.stock})"
            inCart > 0 -> "Añadir otra ($inCart/${record.stock})"
            else -> "Añadir al carrito"
        }
        val addBtn = button(btnLabel, false) {
            if (cart.add(record)) {
                persist()
                render()
                message("¡Añadido al carrito! (${cart.quantity(record.id)} en carrito)")
            } else {
                message("Ya llevas las ${record.stock} unidades disponibles en tu carrito.")
            }
        }.apply { isEnabled = canAdd }
        card.addView(addBtn)
        card.addView(button("Ver detalles", true) { openDetail(record) })
        return card
    }

    private fun openDetail(record: Record) { detailId = record.id; render() }

    private fun showDetail(page: LinearLayout, record: Record) {
        page.addView(link("← Volver") { detailId = 0; render() })
        page.addView(cover(record), LinearLayout.LayoutParams(-1, dp(290)))
        space(page, 20)
        page.addView(text("${record.format.uppercase()}  /  ${record.genre.uppercase()}", 12, green, true))
        page.addView(text(record.title, 32, white, true))
        page.addView(text(record.artist, 20, muted))
        space(page, 16)
        page.addView(text(pesos(record.price), 28, green, true))
        space(page, 12)
        val details = listOfNotNull(record.year?.toString(), record.condition).joinToString(" · ")
        page.addView(text("$details\n${record.stock} disponibles en inventario · ${cart.quantity(record.id)} en tu carrito", 14, muted))
        space(page, 16)
        page.addView(text("Una pieza para tu colección. Explora el sonido de ${record.artist} en formato ${record.format.lowercase()}.", 16, white))
        space(page, 20)
        page.addView(button(if (record.stock == 0) "Agotado" else "Agregar al carrito") {
            if (cart.add(record)) {
                persist()
                render()
                message("Agregado al carrito (${cart.quantity(record.id)} unidades)")
            } else {
                message("Ya agregaste todas las unidades disponibles")
            }
        }.apply { isEnabled = record.stock > cart.quantity(record.id) })
        page.addView(button(if (record.id in favorites) "♥ Guardado en favoritos" else "♡ Guardar en favoritos", true) {
            if (!favorites.add(record.id)) favorites.remove(record.id)
            persist()
            render()
        })
    }

    private fun showCart(page: LinearLayout) {
        page.addView(text("TU PRÓXIMA COLECCIÓN", 11, green, true))
        val header = row()
        header.addView(text("Mi carrito", 32, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (cart.count > 0) {
            header.addView(link("Vaciar carrito") { confirmClearCart() })
        }
        page.addView(header)
        space(page, 8)
        if (catalogStatus(page)) return
        page.addView(text("${cart.count} piezas seleccionadas", 14, muted))
        space(page, 20)
        val cartRecords = records.filter { cart.quantity(it.id) > 0 }
        if (cartRecords.isEmpty()) {
            empty(page, "Tu carrito está vacío", "Explora el catálogo y encuentra tu próxima joya musical.", "Ir al catálogo") { go(2) }
            return
        }
        cartRecords.forEach { record ->
            val card = panel()
            val line = row()
            line.addView(cover(record), LinearLayout.LayoutParams(dp(72), dp(72)))
            val info = column().apply { setPadding(dp(12), 0, 0, 0) }
            info.addView(text(record.title, 17, white, true))
            info.addView(text("${record.artist} · ${record.format}", 12, muted))
            info.addView(text("Unitario: ${pesos(record.price)} · Disponibles: ${record.stock}", 12, muted))
            info.addView(text(pesos(record.price * cart.quantity(record.id).toBigDecimal()), 16, green, true))
            line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(line)
            val controls = row()
            controls.addView(button("−", true) {
                val next = cart.quantity(record.id) - 1
                cart.removeOne(record.id)
                persist()
                render()
                if (next <= 0) message("\"${record.title}\" se quitó del carrito.")
            }.apply {
                contentDescription = "Quitar una unidad de ${record.title}"
            }, LinearLayout.LayoutParams(dp(56), dp(48)))
            controls.addView(text("${cart.quantity(record.id)}", 17, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), -2))
            controls.addView(button("+", true) {
                if (cart.add(record)) {
                    persist()
                    render()
                } else {
                    message("Límite de stock: solo hay ${record.stock} unidades disponibles.")
                }
            }.apply {
                contentDescription = "Agregar una unidad de ${record.title}"
                isEnabled = cart.quantity(record.id) < record.stock
            }, LinearLayout.LayoutParams(dp(56), dp(48)))
            controls.addView(link("Eliminar") { confirmRemoveItem(record) }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(controls)
            page.addView(card)
            space(page, 12)
        }

        val subtotal = cart.total(records)
        val iva = cart.iva(records)
        val totalEstimado = cart.totalConEnvio(records, COSTO_ENVIO_ESTIMADO)
        val session = storeState.session?.takeUnless { it.expired }

        val summary = panel()
        summary.addView(text("Resumen del Pedido", 20, white, true))
        space(summary, 10)
        summary.addView(summaryRow("Subtotal:", pesos(subtotal)))
        summary.addView(summaryRow("IVA (16%):", "+ ${pesos(iva)}", muted))
        summary.addView(summaryRow("Costo de Envío (base):", "+ ${pesos(COSTO_ENVIO_ESTIMADO)}", muted))
        space(summary, 8)
        summary.addView(summaryRow("Total estimado:", pesos(totalEstimado), green, bold = true, textSize = 19))
        space(summary, 6)
        summary.addView(text("En el checkout podrás elegir envío a domicilio por paquetería o entrega gratuita en punto medio (Aguascalientes).", 12, muted))
        space(summary, 12)
        if (session != null) {
            summary.addView(button("Proceder al Checkout") { startCheckout() })
        } else {
            summary.addView(button("Inicia sesión para continuar") {
                go(4)
                auth(false)
            })
        }
        page.addView(summary)
    }

    private fun confirmRemoveItem(record: Record) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Eliminar producto?")
            .setMessage("¿Estás seguro de que deseas eliminar \"${record.title}\" del carrito?")
            .setPositiveButton("Sí, eliminar") { _, _ ->
                cart.remove(record.id)
                persist()
                render()
                message("El producto fue eliminado del carrito.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmClearCart() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Vaciar carrito?")
            .setMessage("Se eliminarán todos los productos de tu carrito.")
            .setPositiveButton("Sí, vaciar") { _, _ ->
                cart.clear()
                persist()
                render()
                message("Carrito vacío.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startCheckout() {
        val session = storeState.session?.takeUnless { it.expired }
        if (session == null) {
            storeState.session = null
            go(4)
            auth(false)
            return
        }
        if (cart.count == 0) {
            message("El carrito está vacío.")
            return
        }
        checkoutOpen = true
        ventaConfirmada = null
        pagoPaypalRealizado = false
        paypalOrderId = null
        submittingPurchase = false
        checkoutFieldErrors = emptyMap()
        checkoutGeneralError = null
        if (contactoCorreo.isBlank()) contactoCorreo = session.email
        if (destNombre.isBlank()) destNombre = session.name
        loadCheckoutData(session)
        render()
    }

    private fun loadCheckoutData(session: Session) {
        loadingCheckoutOptions = true
        render()
        request({
            val tarifasList = api.tarifasEnvio(session)
            val puntosList = api.puntosEntrega(session)
            val perfil = runCatching { api.perfilEnvio(session) }.getOrNull()
            Triple(tarifasList, puntosList, perfil)
        }) { result ->
            loadingCheckoutOptions = false
            result.onSuccess { (tarifasList, puntosList, perfil) ->
                tarifas = tarifasList.ifEmpty { DEFAULT_TARIFAS_ENVIO }
                puntos = puntosList.ifEmpty { DEFAULT_PUNTOS_ENTREGA }
                if (perfil != null) {
                    if (perfil.destinatario.isNotEmpty()) destNombre = perfil.destinatario
                    if (perfil.correo.isNotEmpty()) contactoCorreo = perfil.correo
                    if (perfil.telefono.isNotEmpty()) {
                        destTelefono = perfil.telefono
                        contactoTelefono = perfil.telefono
                    }
                    if (perfil.calle.isNotEmpty()) destCalle = perfil.calle
                    if (perfil.numero.isNotEmpty()) destNumero = perfil.numero
                    if (perfil.colonia.isNotEmpty()) destColonia = perfil.colonia
                    if (perfil.cp.isNotEmpty()) destCp = perfil.cp
                    if (perfil.ciudad.isNotEmpty()) destCiudad = perfil.ciudad
                    if (perfil.estado.isNotEmpty()) destEstado = perfil.estado
                }
            }.onFailure {
                tarifas = DEFAULT_TARIFAS_ENVIO
                puntos = DEFAULT_PUNTOS_ENTREGA
            }
            render()
        }
    }

    private fun tarifaElegida(): TarifaEnvio? {
        if (!selectedEntrega.startsWith("tarifa-")) return null
        val id = selectedEntrega.removePrefix("tarifa-").toIntOrNull() ?: return null
        return tarifas.find { it.idtarifa == id }
    }

    private fun costoEnvioActual(): BigDecimal = tarifaElegida()?.precio ?: BigDecimal.ZERO

    private fun showCheckout(page: LinearLayout) {
        val confirmed = ventaConfirmada
        if (confirmed != null) {
            val successPanel = panel()
            successPanel.addView(text("¡Tu pedido ha sido confirmado!", 26, green, true))
            space(successPanel, 10)
            successPanel.addView(text("Tu número de orden es: #${confirmed.idVenta}", 19, white, true))
            space(successPanel, 6)
            successPanel.addView(text("Total pagado: ${pesos(confirmed.totalVenta)}", 18, green, true))
            space(successPanel, 10)
            successPanel.addView(text("El stock fue actualizado y tu orden quedó registrada en el sistema. Gracias por tu compra.", 14, muted))
            space(successPanel, 18)
            successPanel.addView(button("Ver Mis Pedidos") {
                checkoutOpen = false
                ordersOpen = true
                render()
            })
            successPanel.addView(button("Seguir explorando el catálogo", true) {
                checkoutOpen = false
                go(2)
            })
            page.addView(successPanel)
            return
        }

        page.addView(link("← Volver al carrito") {
            if (!submittingPurchase) {
                checkoutOpen = false
                render()
            }
        })
        page.addView(text("FINALIZAR COMPRA", 11, green, true))
        page.addView(text("Checkout y Pago", 30, white, true))
        space(page, 12)

        checkoutGeneralError?.let { err ->
            val alert = panel().apply { background = rounded(Color.rgb(68, 24, 24), 14) }
            alert.addView(text(err, 14, danger, true))
            page.addView(alert)
            space(page, 12)
        }

        val step1 = panel()
        step1.addView(text("1. ¿Cómo quieres recibir tu pedido?", 19, white, true))
        space(step1, 8)
        if (loadingCheckoutOptions) {
            step1.addView(text("Sincronizando tarifas y puntos de entrega…", 13, muted))
            space(step1, 6)
        }
        step1.addView(text("Envío a domicilio (cualquier estado de la República)", 14, green, true))
        space(step1, 6)
        tarifas.forEach { t ->
            val code = "tarifa-${t.idtarifa}"
            val selected = selectedEntrega == code
            val label = "${if (selected) "●" else "○"} ${t.paqueteria} – ${t.servicio}\nLlega en ${t.diasMin} a ${t.diasMax} días hábiles · ${pesos(t.precio)}"
            step1.addView(button(label, !selected) {
                if (!pagoPaypalRealizado) {
                    selectedEntrega = code
                    checkoutFieldErrors = emptyMap()
                    checkoutGeneralError = null
                    render()
                }
            }.apply {
                isEnabled = !pagoPaypalRealizado
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })
        }
        space(step1, 12)
        step1.addView(text("Entrega en punto medio (Aguascalientes)", 14, green, true))
        space(step1, 6)
        puntos.forEach { p ->
            val code = "punto-${p.idpunto}"
            val selected = selectedEntrega == code
            val horario = if (p.horarioatencion.isNotEmpty()) "\nHorario: ${p.horarioatencion}" else ""
            val label = "${if (selected) "●" else "○"} ${p.nombrepunto} (Gratis)\n${p.direccioncompleta}$horario"
            step1.addView(button(label, !selected) {
                if (!pagoPaypalRealizado) {
                    selectedEntrega = code
                    checkoutFieldErrors = emptyMap()
                    checkoutGeneralError = null
                    render()
                }
            }.apply {
                isEnabled = !pagoPaypalRealizado
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })
        }
        if (pagoPaypalRealizado) {
            space(step1, 6)
            step1.addView(text("La forma de entrega y los datos quedaron protegidos porque el pago ya fue autorizado.", 12, muted))
        }
        page.addView(step1)
        space(page, 14)

        val esDomicilio = selectedEntrega.startsWith("tarifa-")
        val esPuntoMedio = selectedEntrega.startsWith("punto-")

        var syncFormValues: () -> Unit = {}

        if (esDomicilio) {
            val addressCard = panel()
            addressCard.addView(text("Dirección de envío", 19, white, true))
            space(addressCard, 4)
            addressCard.addView(text("Tomamos la dirección de tu perfil; puedes cambiarla solo para este pedido.", 13, muted))
            space(addressCard, 10)

            val fDest = boundInput("Nombre de quien recibe", destNombre, 150, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["destinatario"]) { destNombre = it }
            val fTel = boundInput("Teléfono (10 dígitos)", destTelefono, 10, numeric = true, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["telefono"]) { destTelefono = it }
            val fCalle = boundInput("Calle", destCalle, 100, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["calle"]) { destCalle = it }
            val fNum = boundInput("Número", destNumero, 20, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["numero"]) { destNumero = it }
            val fCol = boundInput("Colonia", destColonia, 100, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["colonia"]) { destColonia = it }
            val fCp = boundInput("Código postal (5 dígitos)", destCp, 5, numeric = true, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["cp"]) { destCp = it }
            val fCiudad = boundInput("Ciudad", destCiudad, 50, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["ciudad"]) { destCiudad = it }
            val fEstado = boundInput("Estado", destEstado, 50, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["estado"]) { destEstado = it }
            val fRef = boundInput("Referencias (opcional)", destReferencias, 300, multiline = true, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["referencias"]) { destReferencias = it }

            listOf(fDest, fTel, fCalle, fNum, fCol, fCp, fCiudad, fEstado, fRef).forEach { addressCard.addView(it.first) }

            syncFormValues = {
                destNombre = fDest.second.text.toString()
                destTelefono = fTel.second.text.toString()
                destCalle = fCalle.second.text.toString()
                destNumero = fNum.second.text.toString()
                destColonia = fCol.second.text.toString()
                destCp = fCp.second.text.toString()
                destCiudad = fCiudad.second.text.toString()
                destEstado = fEstado.second.text.toString()
                destReferencias = fRef.second.text.toString()
            }

            page.addView(addressCard)
            space(page, 14)
        } else if (esPuntoMedio) {
            val contactCard = panel()
            contactCard.addView(text("Datos para la entrega en punto medio", 19, white, true))
            space(contactCard, 4)
            contactCard.addView(text("Te contactaremos para acordar el día y la hora de la entrega.", 13, muted))
            space(contactCard, 10)

            val fCorreo = boundInput("Correo electrónico", contactoCorreo, 150, email = true, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["correo"]) { contactoCorreo = it }
            val fTel = boundInput("Teléfono (10 dígitos)", contactoTelefono, 10, numeric = true, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["telefono"]) { contactoTelefono = it }
            val fRef = boundInput("Referencias para la entrega (obligatorio)", contactoReferencias, 300, multiline = true, enabled = !pagoPaypalRealizado, error = checkoutFieldErrors["referencias"]) { contactoReferencias = it }

            listOf(fCorreo, fTel, fRef).forEach { contactCard.addView(it.first) }

            syncFormValues = {
                contactoCorreo = fCorreo.second.text.toString()
                contactoTelefono = fTel.second.text.toString()
                contactoReferencias = fRef.second.text.toString()
            }

            page.addView(contactCard)
            space(page, 14)
        }

        val cartItems = cart.items(records)
        val subtotal = cart.total(records)
        val iva = cart.iva(records)
        val envio = costoEnvioActual()
        val totalPagar = cart.totalConEnvio(records, envio)

        val txCard = panel()
        txCard.addView(text("Detalles de la Transacción", 19, white, true))
        space(txCard, 8)
        txCard.addView(text("Productos (${cartItems.size} ítems)", 14, green, true))
        space(txCard, 4)
        cartItems.forEach { (itemRecord, qty) ->
            txCard.addView(summaryRow("${qty}x ${itemRecord.title}", pesos(itemRecord.price * qty.toBigDecimal()), white))
        }
        space(txCard, 8)
        txCard.addView(summaryRow("Subtotal:", pesos(subtotal)))
        val envioTexto = when {
            selectedEntrega.isEmpty() -> "Elige una opción"
            envio.compareTo(BigDecimal.ZERO) == 0 -> "Gratis"
            else -> "+ ${pesos(envio)}"
        }
        txCard.addView(summaryRow("Costo de Envío:", envioTexto, if (selectedEntrega.isNotEmpty() && envio.compareTo(BigDecimal.ZERO) == 0) green else muted))
        txCard.addView(summaryRow("IVA (16%):", "+ ${pesos(iva)}", muted))
        space(txCard, 6)
        txCard.addView(summaryRow("Total a Pagar:", pesos(totalPagar), green, bold = true, textSize = 20))
        page.addView(txCard)
        space(page, 14)

        val payCard = panel()
        payCard.addView(text("2. Detalles de Pago", 19, white, true))
        space(payCard, 8)
        if (!pagoPaypalRealizado) {
            payCard.addView(text("Paga con PayPal (Sandbox MXN) para continuar con tu compra.", 14, muted))
            space(payCard, 8)
            payCard.addView(button("Pagar con PayPal · ${pesos(totalPagar)}") {
                syncFormValues()
                iniciarPagoPaypal(totalPagar)
            })
        } else {
            payCard.addView(text("✓ Pago con PayPal aprobado (${paypalOrderId ?: "SANDBOX"}).", 15, green, true))
            payCard.addView(text("Monto autorizado: ${pesos(totalPagar)}. Gracias por tu compra.", 13, muted))
        }
        page.addView(payCard)
        space(page, 14)

        val confirmBtnLabel = if (submittingPurchase) "Procesando Compra…" else "Confirmar Compra"
        page.addView(button(confirmBtnLabel) {
            syncFormValues()
            confirmarCompra(totalPagar)
        }.apply {
            isEnabled = pagoPaypalRealizado && !submittingPurchase && selectedEntrega.isNotEmpty()
        })
    }

    private fun validateCheckoutSelection(): Boolean {
        if (selectedEntrega.isEmpty()) {
            checkoutGeneralError = "Elige cómo quieres recibir tu pedido antes de continuar."
            checkoutFieldErrors = emptyMap()
            render()
            return false
        }
        val validation = if (selectedEntrega.startsWith("tarifa-")) {
            validateDireccionEnvio(
                destinatario = destNombre,
                telefono = destTelefono,
                calle = destCalle,
                numero = destNumero,
                colonia = destColonia,
                cp = destCp,
                ciudad = destCiudad,
                estado = destEstado,
                referencias = destReferencias
            )
        } else {
            validateContactoPunto(
                correo = contactoCorreo,
                telefono = contactoTelefono,
                referencias = contactoReferencias
            )
        }
        checkoutFieldErrors = validation.fieldErrors
        if (!validation.isValid) {
            checkoutGeneralError = if (selectedEntrega.startsWith("punto-")) {
                "Por favor, completa correo, teléfono (10 dígitos) y referencias para la entrega."
            } else {
                "Por favor, completa correctamente la dirección de envío (teléfono de 10 dígitos y CP de 5 dígitos)."
            }
            render()
            return false
        }
        checkoutGeneralError = null
        return true
    }

    private fun iniciarPagoPaypal(totalPagar: BigDecimal) {
        if (!validateCheckoutSelection()) return
        val items = cart.items(records)
        if (items.isEmpty()) {
            checkoutGeneralError = "El carrito está vacío."
            render()
            return
        }
        val outOfStock = items.firstOrNull { (record, qty) -> qty > record.stock || record.stock <= 0 }
        if (outOfStock != null) {
            checkoutGeneralError = "Stock insuficiente para \"${outOfStock.first.title}\". Ajusta tu carrito."
            render()
            return
        }

        val form = column().apply { setPadding(dp(24), dp(12), dp(24), 0) }
        form.addView(text("Comercio: PsychoRekords (Sandbox MXN)", 14, green, true))
        space(form, 6)
        form.addView(text("Importe total a autorizar: ${pesos(totalPagar)}", 18, white, true))
        space(form, 8)
        val buyerEmail = storeState.session?.email.orEmpty().ifEmpty { "comprador-sandbox@personal.example.com" }
        val emailField = boundInput("Cuenta PayPal Sandbox", buyerEmail, 150, email = true)
        form.addView(emailField.first)
        form.addView(text("Al autorizar el pago se congelará la forma de entrega elegida para proteger la transacción.", 12, muted))

        MaterialAlertDialogBuilder(this)
            .setTitle("PayPal Sandbox Checkout")
            .setView(form)
            .setPositiveButton("Autorizar pago") { _, _ ->
                paypalOrderId = "PAYPAL-" + UUID.randomUUID().toString().take(8).uppercase()
                pagoPaypalRealizado = true
                checkoutGeneralError = null
                render()
                message("Pago con PayPal aprobado. Ahora confirma tu compra.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarCompra(totalPagar: BigDecimal) {
        if (!validateCheckoutSelection()) return
        if (!pagoPaypalRealizado) {
            checkoutGeneralError = "Debes completar el pago con PayPal antes de confirmar la compra."
            render()
            return
        }
        val session = storeState.session?.takeUnless { it.expired }
        if (session == null) {
            storeState.session = null
            checkoutOpen = false
            go(4)
            auth(false)
            message("Tu sesión expiró. Inicia sesión de nuevo; tu carrito sigue guardado.")
            return
        }
        val items = cart.items(records)
        if (items.isEmpty()) {
            checkoutGeneralError = "El carrito está vacío. No se puede procesar la compra."
            render()
            return
        }

        submittingPurchase = true
        checkoutGeneralError = null
        render()

        val esPunto = selectedEntrega.startsWith("punto-")
        val idEntrega = selectedEntrega.substringAfter("-").toIntOrNull() ?: 1

        request({
            if (esPunto) {
                api.crearVentaPunto(
                    session = session,
                    idPunto = idEntrega,
                    items = items,
                    contacto = ContactoPunto(
                        correo = contactoCorreo.trim(),
                        telefono = contactoTelefono.trim(),
                        referencias = contactoReferencias.trim()
                    ),
                    expectedTotal = totalPagar
                )
            } else {
                api.crearVentaDomicilio(
                    session = session,
                    idTarifa = idEntrega,
                    items = items,
                    direccion = DireccionEnvio(
                        destinatario = destNombre.trim(),
                        telefono = destTelefono.trim(),
                        calle = destCalle.trim(),
                        numero = destNumero.trim(),
                        colonia = destColonia.trim(),
                        cp = destCp.trim(),
                        ciudad = destCiudad.trim(),
                        estado = destEstado.trim(),
                        referencias = destReferencias.trim()
                    ),
                    expectedTotal = totalPagar
                )
            }
        }) { result ->
            submittingPurchase = false
            result.onSuccess { confirmada ->
                ventaConfirmada = confirmada
                cart.clear()
                persist()
                loadCatalog()
                render()
            }.onFailure { err ->
                if (err is ApiError && err.isUnauthorized) {
                    storeState.session = null
                    checkoutOpen = false
                    go(4)
                    message("Tu sesión expiró o fue revocada. Inicia sesión nuevamente.")
                } else {
                    checkoutGeneralError = errorMessage(err)
                    loadCatalog()
                    render()
                }
            }
        }
    }

    private fun showAccount(page: LinearLayout) {
        brand(page)
        page.addView(text("Tu espacio musical", 30, white, true))
        space(page, 8)
        page.addView(text("Tus favoritos, tus discos, tu historia.", 16, muted))
        space(page, 22)
        val card = panel()
        val session = storeState.session?.takeUnless { it.expired }
        if (session == null) {
            storeState.session = null
            card.addView(text("Bienvenido a PsychoRekords", 21, white, true))
            space(card, 8)
            card.addView(text("Entra con tu cuenta de la web o crea una aquí.", 14, muted))
            card.addView(button("Iniciar sesión") { auth(false) })
            card.addView(button("Crear cuenta", true) { auth(true) })
        } else {
            card.addView(text("Hola, ${session.name}", 21, white, true))
            card.addView(text(session.email, 14, muted))
            card.addView(button("Cambiar contraseña", true) { changePassword(session) })
            card.addView(button("Cerrar sesión", true) {
                storeState.session = null
                render()
                request({ api.logout(session) }) { result ->
                    result.onFailure { message("Sesión cerrada en este equipo. No se pudo avisar al servidor.") }
                }
            })
        }
        page.addView(card)
        space(page, 18)
        page.addView(button("Mis favoritos · ${favorites.size}", true) { onlyFavorites = true; query = ""; format = "Todos"; go(2) })
        page.addView(button("Mis pedidos", true) {
            if (storeState.session?.takeUnless { it.expired } == null) {
                auth(false)
                message("Inicia sesión para consultar tus pedidos.")
            } else {
                ordersOpen = true
                render()
            }
        })
        page.addView(button("Sobre nosotros", true) { about() })
        page.addView(button("Preguntas frecuentes", true) { faq() })
    }

    private fun changePassword(session: Session) {
        if (session.expired) {
            storeState.session = null
            render()
            auth(false)
            return
        }
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), dp(12)) }
        val current = input("Contraseña actual")
        val replacement = input("Nueva contraseña")
        val confirmation = input("Confirmar nueva contraseña")
        val fields = listOf(current, replacement, confirmation)
        fields.forEach { (wrapper, edit) ->
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            edit.typeface = regularFont
            edit.isSaveEnabled = false
            wrapper.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            form.addView(wrapper)
        }
        form.addView(text("Usa al menos 8 caracteres y una contraseña diferente a la actual.", 13, muted))
        val status = text("", 14, muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        form.addView(status)
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Cambiar contraseña")
            .setView(scroll).setPositiveButton("Guardar", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val cancel = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            save.setOnClickListener {
                val old = current.second.text.toString()
                val new = replacement.second.text.toString()
                val repeated = confirmation.second.text.toString()
                current.first.error = when {
                    old.isEmpty() -> "Escribe tu contraseña actual"
                    old.toByteArray(Charsets.UTF_8).size > 256 -> "La contraseña excede 256 bytes"
                    else -> null
                }
                replacement.first.error = when {
                    new.length < 8 -> "Usa al menos 8 caracteres"
                    new.toByteArray(Charsets.UTF_8).size > 256 -> "La contraseña excede 256 bytes"
                    new == old -> "Debe ser diferente a la contraseña actual"
                    else -> null
                }
                confirmation.first.error = if (repeated.isEmpty() || repeated != new) "Las contraseñas no coinciden" else null
                if (fields.any { it.first.error != null }) return@setOnClickListener
                save.isEnabled = false
                cancel.isEnabled = false
                fields.forEach { it.first.isEnabled = false }
                dialog.setCancelable(false)
                status.text = "Guardando…"
                request({ api.changePassword(session, old, new) }) { result ->
                    if (storeState.session?.token != session.token) {
                        dialog.dismiss()
                        return@request
                    }
                    result.onSuccess {
                        fields.forEach { field -> field.second.text?.clear() }
                        dialog.dismiss()
                        message(it)
                    }.onFailure { error ->
                        if (error is ApiError && error.statusCode in listOf(401, 403)) {
                            fields.forEach { it.second.text?.clear() }
                            dialog.dismiss()
                            storeState.session = null
                            render()
                            auth(false, session.email)
                            message("Tu sesión terminó. Inicia sesión para continuar.")
                            return@onFailure
                        }
                        save.isEnabled = true
                        cancel.isEnabled = true
                        fields.forEach { it.first.isEnabled = true }
                        dialog.setCancelable(true)
                        status.text = errorMessage(error)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun auth(register: Boolean, initialEmail: String = "") {
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val name = if (register) input("Nombre") else null
        name?.let { form.addView(it.first) }
        val surname = if (register) input("Apellido paterno") else null
        surname?.let { form.addView(it.first) }
        val email = input("Correo electrónico", initialEmail)
        email.second.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        form.addView(email.first)
        val password = input("Contraseña")
        password.second.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.first.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        form.addView(password.first)
        val status = text("", 14, muted)
        form.addView(status)
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (register) "Crear cuenta" else "Iniciar sesión")
            .setView(scroll).setPositiveButton(if (register) "Crear cuenta" else "Entrar", null)
            .setNegativeButton("Cerrar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = email.second.text.toString().trim()
                val secret = password.second.text.toString()
                val firstName = name?.second?.text.toString().trim()
                val lastName = surname?.second?.text.toString().trim()
                val validEmail = address.length <= 100 && android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()
                email.first.error = if (validEmail) null else "Escribe un correo válido"
                val validPassword = secret.length >= (if (register) 8 else 1) && secret.toByteArray().size <= 256
                password.first.error = if (validPassword) null else if (register) "Usa al menos 8 caracteres y hasta 256 bytes" else "Revisa tu contraseña"
                val validName = !register || firstName.length in 1..50
                val validSurname = !register || lastName.length in 1..50
                name?.first?.error = if (validName) null else "Escribe tu nombre (máximo 50 caracteres)"
                surname?.first?.error = if (validSurname) null else "Escribe tu apellido (máximo 50 caracteres)"
                if (!validEmail || !validPassword || !validName || !validSurname) return@setOnClickListener

                val submit = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                val cancel = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                submit.isEnabled = false
                cancel.isEnabled = false
                dialog.setCancelable(false)
                status.text = "Conectando…"
                request({
                    if (register) {
                        api.register(firstName, lastName, address, secret)
                        null
                    } else api.login(address, secret)
                }) { result ->
                    result.onSuccess { session ->
                        storeState.session = session
                        dialog.dismiss()
                        render()
                        if (register) {
                            auth(false, address)
                            message("Cuenta creada. Ya puedes entrar en la app y en la web.")
                        }
                    }.onFailure {
                        submit.isEnabled = true
                        cancel.isEnabled = true
                        dialog.setCancelable(true)
                        status.text = errorMessage(it)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun about() {
        MaterialAlertDialogBuilder(this).setTitle("Sobre PsychoRekords")
            .setMessage("Somos un emprendimiento dedicado a preservar y celebrar la música en sus formatos clásicos: vinilos, cassettes y CDs.\n\nSeleccionamos piezas para rescatar la calidez del sonido analógico y mantener viva la cultura de lo retro.")
            .setPositiveButton("Seguir escuchando", null).show()
    }

    private fun faq() {
        val questions = arrayOf("¿Qué formatos encontraré?", "¿Cómo hago un pedido?", "¿Cómo cuido mis vinilos?")
        val answers = listOf(
            "Vinilos, CDs y cassettes, como en nuestro catálogo web.",
            "Elige tus piezas en el catálogo, agrégalas al carrito, selecciona envío a domicilio o entrega en punto medio en Aguascalientes, autoriza tu pago con PayPal y confirma tu pedido.",
            "Usa un cepillo antiestático, evita tocar los surcos y guarda tus discos en sus fundas, en posición vertical."
        )
        MaterialAlertDialogBuilder(this).setTitle("Preguntas frecuentes").setItems(questions) { _, which ->
            MaterialAlertDialogBuilder(this).setTitle(questions[which]).setMessage(answers[which])
                .setPositiveButton("Entendido", null).show()
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun cover(record: Record): View {
        if (record.cover != 0) return ImageView(this).apply {
            setImageResource(record.cover); scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Ver ${record.title}, de ${record.artist}"
            background = rounded(surface, 12); clipToOutline = true
        }
        return FrameLayout(this).apply {
            background = rounded(if (record.id % 2 == 0) Color.rgb(37, 66, 77) else Color.rgb(58, 51, 69), 12)
            contentDescription = "Ver ${record.title}, portada de muestra"
            addView(text("◎", 100, green, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            addView(text("${record.artist}\n${record.title}", 13, white, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(8))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
    }

    private fun input(label: String, value: String = ""): Pair<TextInputLayout, TextInputEditText> {
        val wrapper = TextInputLayout(this).apply {
            hint = label; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val edit = TextInputEditText(wrapper.context).apply {
            setText(value); setTextColor(white); textSize = 16f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = regularFont
            minHeight = dp(56)
        }
        wrapper.addView(edit)
        return wrapper to edit
    }

    private fun boundInput(
        label: String,
        value: String,
        maxLength: Int,
        numeric: Boolean = false,
        email: Boolean = false,
        multiline: Boolean = false,
        enabled: Boolean = true,
        error: String? = null,
        onChanged: (String) -> Unit = {}
    ): Pair<TextInputLayout, TextInputEditText> {
        val pair = input(label, value)
        pair.first.error = error
        pair.second.isEnabled = enabled
        pair.second.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        if (multiline) {
            pair.second.isSingleLine = false
            pair.second.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            pair.second.minLines = 2
            pair.second.maxLines = 3
            pair.second.gravity = Gravity.TOP or Gravity.START
        } else {
            pair.second.inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        pair.second.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.postDelayed({
                    val rect = android.graphics.Rect(0, 0, v.width, v.height + dp(48))
                    v.requestRectangleOnScreen(rect, false)
                }, 180)
            }
        }
        pair.second.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return pair
    }

    private fun summaryRow(
        left: String,
        right: String,
        rightColor: Int = white,
        bold: Boolean = false,
        textSize: Int = 14
    ): LinearLayout {
        val line = row().apply { setPadding(0, dp(2), 0, dp(2)) }
        line.addView(text(left, textSize, if (bold) white else muted, bold), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text(right, textSize, rightColor, bold))
        return line
    }

    private fun empty(parent: LinearLayout, title: String, description: String, action: String, onClick: () -> Unit) {
        val panel = panel()
        panel.addView(text("◎", 76, green).apply { gravity = Gravity.CENTER })
        panel.addView(text(title, 23, white, true))
        space(panel, 10)
        panel.addView(text(description, 15, muted))
        space(panel, 16)
        panel.addView(button(action, action = onClick))
        parent.addView(panel)
    }

    private fun loadCatalog() {
        if (records.isEmpty()) {
            loadingCatalog = true
            catalogError = null
            render()
        }
        request(api::products) { result ->
            loadingCatalog = false
            result.onSuccess { products ->
                records = products
                cart.clampToStock(records)
                persist()
            }.onFailure {
                if (records.isEmpty()) catalogError = errorMessage(it)
            }
            render()
        }
    }

    private fun catalogStatus(page: LinearLayout): Boolean {
        if (loadingCatalog) {
            page.addView(text("Cargando catálogo…", 15, muted))
            return true
        }
        catalogError?.let {
            empty(page, "No se pudo cargar el catálogo", it, "Reintentar") { loadCatalog() }
            return true
        }
        if (records.isEmpty()) {
            empty(page, "Catálogo vacío", "Aún no hay productos registrados.", "Actualizar") { loadCatalog() }
            return true
        }
        return false
    }

    private fun errorMessage(error: Throwable): String =
        if (error is ApiError) error.message.orEmpty()
        else "No se pudo consultar la API. Revisa que el servidor local esté disponible."

    private fun <T> request(operation: () -> T, complete: (Result<T>) -> Unit) {
        storeState.network.execute {
            val result = runCatching(operation)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) complete(result)
            }
        }
    }

    private fun message(value: String) {
        val snackbar = Snackbar.make(container, value, Snackbar.LENGTH_LONG)
        if (nav.visibility == View.VISIBLE) snackbar.setAnchorView(nav)
        snackbar.show()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun panel() = column().apply { background = rounded(surface); setPadding(dp(18), dp(18), dp(18), dp(18)) }
    private fun rounded(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun text(value: String, size: Int, color: Int = white, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = if (bold) emphasisFont else regularFont
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(label: String, outline: Boolean = false, action: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(48); cornerRadius = dp(14)
        typeface = emphasisFont
        insetTop = dp(4); insetBottom = dp(4)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        backgroundTintList = ColorStateList.valueOf(if (outline) surface else green)
        setTextColor(if (outline) green else dark)
        if (outline) { strokeWidth = dp(1); strokeColor = ColorStateList.valueOf(Color.rgb(51, 87, 65)) }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnClickListener { action() }
    }
    private fun link(label: String, action: () -> Unit) = text(label, 14, green, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(6), 0, dp(6), 0)
        isFocusable = true
        val typed = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        setBackgroundResource(typed.resourceId)
        setOnClickListener { action() }
    }
}
