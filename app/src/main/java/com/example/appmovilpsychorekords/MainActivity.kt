package com.example.appmovilpsychorekords

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private val green = Color.rgb(60, 234, 95)
    private val dark = Color.rgb(3, 26, 22)
    private val surface = Color.rgb(16, 43, 37)
    private val muted = Color.rgb(179, 198, 188)
    private val white = Color.rgb(245, 250, 244)
    private val regularFont by lazy { ResourcesCompat.getFont(this, R.font.nunito_family)!! }
    private val emphasisFont by lazy { Typeface.create(regularFont, 600, false) }
    private val cart = ShoppingCart()
    private val favorites = mutableSetOf<Int>()
    private val prefs by lazy { getSharedPreferences("psycho_catalog", MODE_PRIVATE) }
    private val storeState by viewModels<StoreState>()
    private val api by lazy { PsychoApi(getString(R.string.psycho_api_url)) }
    private var records = emptyList<Record>()
    private var loadingCatalog = true
    private var catalogError: String? = null
    private lateinit var container: FrameLayout
    private lateinit var nav: BottomNavigationView
    private var tab = 1
    private var detailId = 0
    private var query = ""
    private var format = "Todos"
    private var onlyFavorites = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        favorites.addAll(prefs.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() })
        tab = savedInstanceState?.getInt("tab", 1) ?: 1
        detailId = savedInstanceState?.getInt("detail", 0) ?: 0
        query = savedInstanceState?.getString("query").orEmpty()
        format = savedInstanceState?.getString("format") ?: "Todos"
        onlyFavorites = savedInstanceState?.getBoolean("favorites", false) ?: false
        container = findViewById(R.id.screen_container)
        nav = findViewById(R.id.bottom_navigation)
        listOf("Inicio", "Catálogo", "Carrito", "Cuenta").forEachIndexed { index, title ->
            nav.menu.add(0, index + 1, index, title).setIcon(
                listOf(R.drawable.ic_home, R.drawable.ic_records, R.drawable.ic_cart, R.drawable.ic_person)[index])
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
            tab = item.itemId
            detailId = 0
            render()
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detailId != 0) { detailId = 0; render() }
                else if (tab != 1) go(1)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        render()
        loadCatalog()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("detail", detailId)
        outState.putString("query", query)
        outState.putString("format", format)
        outState.putBoolean("favorites", onlyFavorites)
    }

    private fun persist() {
        prefs.edit().apply {
            records.forEach { putInt("cart_${it.id}", cart.quantity(it.id)) }
            putStringSet("favorites", favorites.map { it.toString() }.toSet())
        }.apply()
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
        tab = destination
        nav.selectedItemId = destination
        render()
    }

    private fun render() {
        container.removeAllViews()
        updateBadge()
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val page = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(page)
        container.addView(scroll)
        val record = records.find { it.id == detailId }
        if (record != null) showDetail(page, record)
        else when (tab) {
            1 -> showHome(page)
            2 -> showCatalog(page)
            3 -> showCart(page)
            4 -> showAccount(page)
        }
    }

    private fun brand(page: LinearLayout) {
        val row = row()
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_rana); contentDescription = "Logo de PsychoRekords"
            // Fondo solo para el PNG: mantiene legible el contorno oscuro de la rana.
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33031A16, 0xF5031A16.toInt()))
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
        page.addView(text("Catálogo", 32, white, true))
        space(page, 16)
        if (catalogStatus(page)) return
        page.addView(link("Actualizar catálogo") { loadCatalog() })
        val search = input("Buscar artista, álbum o género", query)
        page.addView(search.first)
        space(page, 10)
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filters = row()
        horizontal.addView(filters)
        page.addView(horizontal)
        listOf("Todos", "Vinilo", "CD", "Cassette").forEach { label ->
            filters.addView(button(label, format != label) {
                query = search.second.text.toString(); format = label; render()
            }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        }
        val favoriteToggle = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            typeface = regularFont
            text = "Solo mis favoritos"; setTextColor(muted); isChecked = onlyFavorites; minHeight = dp(48)
        }
        page.addView(favoriteToggle)
        val results = text("", 12, muted)
        page.addView(results)
        space(page, 12)
        val list = column()
        page.addView(list)
        fun refresh() {
            list.removeAllViews()
            val records = records.filterCatalog(query, format, if (onlyFavorites) favorites else null)
            results.text = "${records.size} piezas"
            if (records.isEmpty()) empty(list, "Sin coincidencias", "Prueba otro artista, formato o desactiva favoritos.", "Limpiar filtros") {
                query = ""; format = "Todos"; onlyFavorites = false; render()
            } else productGrid(list, records)
        }
        search.second.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s.toString(); refresh()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        favoriteToggle.setOnCheckedChangeListener { _, checked -> onlyFavorites = checked; refresh() }
        refresh()
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
        space(card, 4)
        card.addView(text(pesos(record.price), 16, white, true))
        card.addView(button("Ver disco", true) { openDetail(record) })
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
        page.addView(text("$details\n${record.stock} disponibles · ${cart.quantity(record.id)} en tu carrito", 14, muted))
        space(page, 16)
        page.addView(text("Una pieza para tu colección. Explora el sonido de ${record.artist} en formato ${record.format.lowercase()}.", 16, white))
        space(page, 20)
        page.addView(button(if (record.stock == 0) "Agotado" else "Agregar al carrito") {
            if (cart.add(record)) { persist(); render(); message("Agregado al carrito") }
            else message("Ya agregaste todas las unidades disponibles")
        }.apply { isEnabled = record.stock > cart.quantity(record.id) })
        page.addView(button(if (record.id in favorites) "♥ Guardado en favoritos" else "♡ Guardar en favoritos", true) {
            if (!favorites.add(record.id)) favorites.remove(record.id)
            persist(); render()
        })
    }

    private fun showCart(page: LinearLayout) {
        page.addView(text("TU PRÓXIMA COLECCIÓN", 11, green, true))
        page.addView(text("Mi carrito", 32, white, true))
        space(page, 8)
        if (catalogStatus(page)) return
        page.addView(text("${cart.count} piezas seleccionadas", 14, muted))
        space(page, 20)
        if (cart.count == 0) {
            empty(page, "El primer disco te espera", "Explora el catálogo y empieza tu colección.", "Descubrir música") { go(2) }
            return
        }
        records.filter { cart.quantity(it.id) > 0 }.forEach { record ->
            val card = panel()
            val line = row()
            line.addView(cover(record), LinearLayout.LayoutParams(dp(72), dp(72)))
            val info = column().apply { setPadding(dp(12), 0, 0, 0) }
            info.addView(text(record.title, 17, white, true))
            info.addView(text("${record.artist} · ${record.format}", 12, muted))
            info.addView(text(pesos(record.price * cart.quantity(record.id).toBigDecimal()), 16, green, true))
            line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(line)
            val controls = row()
            controls.addView(button("−", true) { cart.removeOne(record.id); persist(); render() }.apply {
                contentDescription = "Quitar una unidad de ${record.title}"
            }, LinearLayout.LayoutParams(dp(56), dp(48)))
            controls.addView(text("${cart.quantity(record.id)}", 17, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), -2))
            controls.addView(button("+", true) { cart.add(record); persist(); render() }.apply {
                contentDescription = "Agregar una unidad de ${record.title}"
                isEnabled = cart.quantity(record.id) < record.stock
            }, LinearLayout.LayoutParams(dp(56), dp(48)))
            controls.addView(link("Eliminar") { cart.remove(record.id); persist(); render() }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(controls)
            page.addView(card)
            space(page, 12)
        }
        val summary = panel()
        summary.addView(text("Subtotal     ${pesos(cart.total(records))}", 22, white, true))
        space(summary, 8)
        summary.addView(text("Envío pendiente de calcular.\nEsta vista no realiza cobros ni genera pedidos.", 13, muted))
        summary.addView(button("Revisar pedido de muestra") { checkout() })
        page.addView(summary)
    }

    private fun checkout() {
        MaterialAlertDialogBuilder(this).setTitle("Resumen de demostración")
            .setMessage("${cart.count} piezas\nSubtotal: ${pesos(cart.total(records))}\n\nLa dirección, el envío y el pago se conectarán al backend en la siguiente etapa. Tu carrito se conserva.")
            .setPositiveButton("Seguir explorando") { _, _ -> go(2) }.setNegativeButton("Volver", null).show()
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
            MaterialAlertDialogBuilder(this).setTitle("Mis pedidos")
                .setMessage("El historial de pedidos se puede consultar desde la web.")
                .setPositiveButton("Entendido", null).show()
        })
        page.addView(button("Sobre nosotros", true) { about() })
        page.addView(button("Preguntas frecuentes", true) { faq() })
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
        val answers = listOf("Vinilos, CDs y cassettes, como en nuestro catálogo web.",
            "Elige una pieza, agrégala al carrito y revisa el resumen. En esta primera versión no se realizan compras.",
            "Usa un cepillo antiestático, evita tocar los surcos y guarda tus discos en sus fundas, en posición vertical.")
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
        loadingCatalog = true
        catalogError = null
        render()
        request(api::products) { result ->
            loadingCatalog = false
            result.onSuccess { products ->
                records = products
                cart.restore(records) { prefs.getInt("cart_$it", 0) }
                persist()
            }.onFailure { catalogError = errorMessage(it) }
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

    private fun message(value: String) { Snackbar.make(container, value, Snackbar.LENGTH_LONG).setAnchorView(nav).show() }
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
        setPadding(dp(12), 0, dp(12), 0)
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
