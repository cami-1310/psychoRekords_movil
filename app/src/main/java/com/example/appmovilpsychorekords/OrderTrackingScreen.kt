package com.example.appmovilpsychorekords

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService

class OrderTrackingScreen(
    private val activity: Activity,
    private val owner: LifecycleOwner,
    private val api: PsychoApi,
    private val session: Session,
    private val network: ExecutorService,
    private val isCurrentSession: () -> Boolean,
    private val onExpired: () -> Unit,
    onBack: () -> Unit
) : LinearLayout(activity), DefaultLifecycleObserver {
    private val green = activity.getColor(R.color.psycho_green)
    private val muted = activity.getColor(R.color.psycho_muted)
    private val primaryTextColor = activity.getColor(R.color.psycho_text)
    private val dark = activity.getColor(R.color.psycho_background)
    private val font = ResourcesCompat.getFont(activity, R.font.nunito_family)
    private val handler = Handler(Looper.getMainLooper())
    private val list = column()
    private val status = label("Consultando pedidos…", 13)
    private val refresh = action("Actualizar ahora") { load() }
    private val scroll = ScrollView(activity)
    private var busy = false
    private var orders: List<TrackedOrder>? = null
    private val expanded = mutableSetOf<Int>()
    private val poll = Runnable { load() }

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(12), dp(20), 0)
        addView(action("← Volver a Cuenta", onBack))
        addView(label("Mis pedidos", 30, true))
        addView(label("Sigue el proceso de entrega de tus compras.", 15))
        addView(status)
        addView(refresh)
        scroll.addView(list)
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        owner.lifecycle.addObserver(this)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        owner.lifecycle.removeObserver(this)
        super.onDetachedFromWindow()
    }

    override fun onResume(owner: LifecycleOwner) { load() }
    override fun onPause(owner: LifecycleOwner) { handler.removeCallbacks(poll) }

    private fun load() {
        if (busy || !isAttachedToWindow || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        handler.removeCallbacks(poll)
        if (!isCurrentSession()) return
        if (session.expired) {
            onExpired()
            return
        }
        busy = true
        refresh.isEnabled = false
        network.execute {
            val result = runCatching { api.orders(session) }
            handler.post {
                busy = false
                if (!isAttachedToWindow || !isCurrentSession()) return@post
                refresh.isEnabled = true
                result.onSuccess {
                    if (orders != it) {
                        orders = it
                        drawOrders()
                    }
                    val time = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    status.text = "Actualizado a las $time · revisión automática cada 15 s"
                }.onFailure {
                    if (it is ApiError && it.statusCode in listOf(401, 403)) {
                        onExpired()
                        return@post
                    }
                    status.text = if (orders == null) "No se pudieron cargar los pedidos. Reintenta."
                        else "Sin conexión. Se muestra la última consulta. Pulsa Actualizar para reintentar."
                }
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    handler.postDelayed(poll, 15000)
                }
            }
        }
    }

    private fun drawOrders() {
        val y = scroll.scrollY
        list.removeAllViews()
        val current = orders.orEmpty()
        if (current.isEmpty()) {
            list.addView(label("Todavía no tienes pedidos.", 20, true))
            list.addView(label("Aquí aparecerán también los pedidos que hagas desde la web.", 15))
        }
        current.forEach { order ->
            val card = column().apply {
                setPadding(dp(16), dp(14), dp(16), dp(18))
                background = GradientDrawable().apply {
                    setColor(activity.getColor(R.color.psycho_surface))
                    cornerRadius = dp(18).toFloat()
                }
            }
            card.addView(label("Pedido #${order.id}", 21, true))
            card.addView(label(formatDate(order.date), 13))
            card.addView(label("${order.lines.sumOf { it.quantity }} piezas · ${pesos(order.total)}", 15))
            card.addView(label(order.status, 18, true).apply {
                setTextColor(if (order.cancelled) 0xFFFFB4AB.toInt() else green)
            })
            if (order.cancelled) {
                card.addView(label("Este pedido fue cancelado.", 14))
            } else if (order.stage >= 0) {
                ORDER_STAGES.forEachIndexed { index, stage ->
                    val marker = when {
                        index < order.stage -> "✓"
                        index == order.stage -> "●"
                        else -> "○"
                    }
                    val suffix = when {
                        index < order.stage -> " · completado"
                        index == order.stage -> " · estado actual"
                        else -> " · pendiente"
                    }
                    card.addView(label("$marker  $stage$suffix", 14, index == order.stage).apply {
                        setTextColor(if (index <= order.stage) green else muted)
                    })
                }
            }
            if (!order.cancelled && order.trackingNumber != null) {
                card.addView(label("${order.carrier.orEmpty()} · Guía ${order.trackingNumber}", 15, true))
                card.addView(action("Copiar guía") {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Guía del pedido", order.trackingNumber))
                    Toast.makeText(activity, "Guía copiada", Toast.LENGTH_SHORT).show()
                })
                trackingUrl(order)?.let { url ->
                    card.addView(action("Abrir rastreo de paquetería") {
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(activity, "No se pudo abrir el navegador", Toast.LENGTH_LONG).show() }
                    })
                }
            }
            val open = order.id in expanded
            card.addView(action(if (open) "Ocultar detalle" else "Ver entrega y productos") {
                if (!expanded.add(order.id)) expanded.remove(order.id)
                drawOrders()
            })
            if (open) {
                card.addView(label(order.delivery, 15))
                order.lines.forEach { line ->
                    card.addView(label("${line.quantity} × ${line.title}\n${line.artist} · ${pesos(line.unitPrice)} c/u", 14))
                }
            }
            list.addView(card, LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun trackingUrl(order: TrackedOrder): String? = when (order.carrier) {
        "DHL" -> "https://www.dhl.com/mx-es/home/rastreo.html?tracking-id=${Uri.encode(order.trackingNumber)}&submit=1"
        "Estafeta" -> "https://www.estafeta.com/Herramientas/Rastreo"
        "Correos de México" -> "https://www.correosdemexico.gob.mx/SSLServicios/SeguimientoEnvio/Seguimiento.aspx"
        else -> null
    }

    private fun formatDate(value: String): String = runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }.getOrDefault(value)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = VERTICAL }
    private fun label(value: String, size: Int, strong: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size.toFloat()
        typeface = if (strong) Typeface.create(font, 600, false) else font
        setTextColor(if (strong) primaryTextColor else muted)
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun action(value: String, clicked: () -> Unit) = MaterialButton(activity).apply {
        text = value
        isAllCaps = false
        typeface = font
        minHeight = dp(48)
        cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(green)
        setTextColor(dark)
        layoutParams = LayoutParams(-1, -2)
        setOnClickListener { clicked() }
    }
}
