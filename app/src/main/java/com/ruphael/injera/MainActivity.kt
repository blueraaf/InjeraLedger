package com.ruphael.injera

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Entry(val id: String, val type: String, val qty: Int, val ts: Long)

/** Draws the balance as tally marks: four bars and a slash per group of five. */
class TallyView(context: Context) : View(context) {
    var count = 0
        set(value) { field = value; requestLayout(); invalidate() }
    var color = Color.BLACK
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val d = context.resources.displayMetrics.density
    private val cap = 40

    private fun groups() = (minOf(count, cap) + 4) / 5
    private fun rows() = (groups() + 3) / 4

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), (rows() * 48 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        paint.strokeWidth = 3 * d
        var remaining = minOf(count, cap)
        for (g in 0 until groups()) {
            val x0 = (g % 4) * 46 * d
            val y0 = (g / 4) * 48 * d
            val bars = minOf(remaining, 4)
            for (i in 0 until bars) {
                val x = x0 + (5 + i * 8) * d
                canvas.drawLine(x, y0 + 4 * d, x, y0 + 36 * d, paint)
            }
            if (remaining >= 5) {
                canvas.drawLine(x0 + 2 * d, y0 + 30 * d, x0 + 36 * d, y0 + 10 * d, paint)
            }
            remaining -= minOf(remaining, 5)
        }
    }
}

class MainActivity : Activity() {

    private var bg = 0
    private var surface = 0
    private var ink = 0
    private var muted = 0
    private var line = 0
    private var owe = 0
    private var paid = 0
    private var warnBg = 0
    private var warnInk = 0
    private var isNight = false

    private fun applyPalette() {
        isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (isNight) {
            bg = Color.parseColor("#171513")
            surface = Color.parseColor("#211E1B")
            ink = Color.parseColor("#EFEBE5")
            muted = Color.parseColor("#A19A90")
            line = Color.parseColor("#38332E")
            owe = Color.parseColor("#F0765C")
            paid = Color.parseColor("#6FCB9F")
            warnBg = Color.parseColor("#3A2E0E")
            warnInk = Color.parseColor("#F3D27A")
        } else {
            bg = Color.parseColor("#EDECE8")
            surface = Color.parseColor("#FBFAF8")
            ink = Color.parseColor("#1F1B17")
            muted = Color.parseColor("#6B645C")
            line = Color.parseColor("#D9D5CE")
            owe = Color.parseColor("#B0331B")
            paid = Color.parseColor("#2D6A4F")
            warnBg = Color.parseColor("#FFF1CC")
            warnInk = Color.parseColor("#5E4200")
        }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val entries = mutableListOf<Entry>()
    private var price = 0.0
    private var mode = "purchase"     // "purchase" or "payment"
    private var customTs: Long? = null

    private lateinit var balLabel: TextView
    private lateinit var balNum: TextView
    private lateinit var tally: TallyView
    private lateinit var tallyNote: TextView
    private lateinit var moneyView: TextView
    private lateinit var tabBuy: Button
    private lateinit var tabPay: Button
    private lateinit var qtyEdit: EditText
    private lateinit var whenView: TextView
    private lateinit var changeTime: TextView
    private lateinit var dupNotice: TextView
    private lateinit var saveBtn: Button
    private lateinit var statusView: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var priceEdit: EditText

    private val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPalette()
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (isNight) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        load()
        setContentView(buildUi())
        render()
    }

    override fun onResume() {
        super.onResume()
        render()   // refreshes "Now" and the same-day check after midnight
    }

    // ---------- storage ----------

    private fun prefs() = getSharedPreferences("injera", Context.MODE_PRIVATE)

    private fun load() {
        val raw = prefs().getString("data", null) ?: return
        try {
            val o = JSONObject(raw)
            price = o.optDouble("price", 0.0)
            val a = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                entries.add(Entry(e.getString("id"), e.getString("type"), e.getInt("qty"), e.getLong("ts")))
            }
        } catch (ex: Exception) {
            // unreadable data: start empty rather than crash
        }
    }

    private fun save() {
        val a = JSONArray()
        entries.forEach {
            a.put(JSONObject().put("id", it.id).put("type", it.type).put("qty", it.qty).put("ts", it.ts))
        }
        val o = JSONObject().put("price", price).put("entries", a)
        prefs().edit().putString("data", o.toString()).apply()
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun tv(text: String = "", size: Float = 16f, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun shape(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun button(text: String, size: Float = 16f) = Button(this).apply {
        this.text = text
        textSize = size
        isAllCaps = false
        stateListAnimator = null
    }

    private fun dayKey(ts: Long) = keyFmt.format(Date(ts))
    private fun timeStr(ts: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
    private fun dateStr(ts: Long) = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(ts))
    private fun dayWord(ts: Long) = if (dayKey(ts) == dayKey(System.currentTimeMillis())) "today" else dateStr(ts)

    private fun dayHeading(key: String, sample: Long): String {
        val now = System.currentTimeMillis()
        return when (key) {
            dayKey(now) -> "Today"
            dayKey(now - 86_400_000L) -> "Yesterday"
            else -> SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date(sample))
        }
    }

    private fun purchasesOn(ts: Long): List<Entry> {
        val k = dayKey(ts)
        return entries.filter { it.type == "purchase" && dayKey(it.ts) == k }.sortedBy { it.ts }
    }

    private fun setStatus(msg: String, isError: Boolean = false) {
        statusView.text = msg
        statusView.setTextColor(if (isError) owe else muted)
        statusView.postDelayed({ statusView.text = "" }, 4000)
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))

        // balance
        balLabel = tv("You owe", 16f, muted)
        root.addView(balLabel, lp())
        val figure = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        balNum = tv("0", 72f, ink, true)
        figure.addView(balNum, LinearLayout.LayoutParams(WRAP, WRAP))
        figure.addView(tv("  injera", 22f, muted), LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(figure, lp())
        tally = TallyView(this)
        root.addView(tally, lp(top = 8))
        tallyNote = tv("", 15f, muted)
        root.addView(tallyNote, lp(top = 2))
        moneyView = tv("", 16f, muted)
        root.addView(moneyView, lp(top = 8))

        // entry card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(surface, 16, line)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(card, lp(top = 20))

        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabBuy = button("Bought").apply { setOnClickListener { mode = "purchase"; render() } }
        tabPay = button("Paid").apply { setOnClickListener { mode = "payment"; render() } }
        seg.addView(tabBuy, lp(0, dp(48), weight = 1f))
        seg.addView(tabPay, lp(0, dp(48), weight = 1f).apply { leftMargin = dp(8) })
        card.addView(seg, lp())

        val qtyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val minus = button("\u2212", 24f).apply {
            background = shape(bg, 12, line); setTextColor(ink)
            setOnClickListener { qtyEdit.setText(maxOf(1, readQty() - 1).toString()) }
        }
        val plus = button("+", 24f).apply {
            background = shape(bg, 12, line); setTextColor(ink)
            setOnClickListener { qtyEdit.setText(minOf(999, readQty() + 1).toString()) }
        }
        qtyEdit = EditText(this).apply {
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            background = shape(bg, 12, line)
            filters = arrayOf(InputFilter.LengthFilter(3))
            setSelectAllOnFocus(true)
        }
        qtyRow.addView(minus, lp(dp(56), dp(56)))
        qtyRow.addView(qtyEdit, lp(0, dp(56), weight = 1f).apply { leftMargin = dp(10); rightMargin = dp(10) })
        qtyRow.addView(plus, lp(dp(56), dp(56)))
        card.addView(qtyRow, lp(top = 16))

        val whenRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        whenView = tv("", 15f, muted)
        changeTime = tv("Change time", 15f, ink).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(dp(8), dp(10), 0, dp(10))
            setOnClickListener { if (customTs != null) { customTs = null; render() } else pickTime() }
        }
        whenRow.addView(whenView, lp(0, WRAP, weight = 1f))
        whenRow.addView(changeTime, LinearLayout.LayoutParams(WRAP, WRAP))
        card.addView(whenRow, lp(top = 6))

        dupNotice = tv("", 15f, warnInk).apply {
            background = shape(warnBg, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        card.addView(dupNotice, lp(top = 8))

        saveBtn = button("Log purchase", 18f).apply {
            background = shape(ink, 12)
            setTextColor(surface)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { submit() }
        }
        card.addView(saveBtn, lp(h = dp(56), top = 16))

        statusView = tv("", 14f, muted)
        card.addView(statusView, lp(top = 8))

        // history
        root.addView(tv("History", 15f, muted), lp(top = 28))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox, lp(top = 6))

        // price
        val priceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        priceRow.addView(tv("Price per injera (Birr)", 15f, muted), lp(0, WRAP, weight = 1f))
        priceEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.END
            setTextColor(ink)
            hint = "0"
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = shape(surface, 10, line)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnEditorActionListener { v, _, _ -> v.clearFocus(); hideKeyboard(); true }
            setOnFocusChangeListener { _, focused ->
                if (!focused) {
                    price = text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0
                    save(); render()
                }
            }
        }
        priceRow.addView(priceEdit, LinearLayout.LayoutParams(dp(110), WRAP))
        root.addView(priceRow, lp(top = 28))

        root.addView(tv("Saved on this phone only.", 13f, muted), lp(top = 16))
        return scroll
    }

    // ---------- rendering ----------

    private fun render() {
        val bought = entries.filter { it.type == "purchase" }.sumOf { it.qty }
        val paidQty = entries.filter { it.type == "payment" }.sumOf { it.qty }
        val bal = bought - paidQty
        val abs = Math.abs(bal)

        balLabel.text = if (bal < 0) "You are ahead by" else "You owe"
        balNum.text = abs.toString()
        val accent = if (bal < 0) paid else owe
        balNum.setTextColor(if (bal == 0) ink else accent)
        tally.color = accent
        tally.count = abs
        tallyNote.text = when {
            abs == 0 -> "All settled."
            abs > 40 -> "+${abs - 40} more"
            else -> ""
        }
        tallyNote.visibility = if (tallyNote.text.isEmpty()) View.GONE else View.VISIBLE
        if (price > 0 && abs > 0) {
            moneyView.visibility = View.VISIBLE
            moneyView.text = (if (bal < 0) "Credit of " else "About ") +
                String.format(Locale.getDefault(), "%,.2f", abs * price) + " Birr"
        } else moneyView.visibility = View.GONE

        // form
        val on = { b: Button, active: Boolean ->
            b.background = shape(if (active) ink else bg, 10, line)
            b.setTextColor(if (active) surface else muted)
        }
        on(tabBuy, mode == "purchase")
        on(tabPay, mode == "payment")
        saveBtn.text = if (mode == "purchase") "Log purchase" else "Record payment"

        val ts = customTs ?: System.currentTimeMillis()
        whenView.text = (if (customTs == null) "Now, " else "") + dateStr(ts) + ", " + timeStr(ts)
        changeTime.text = if (customTs == null) "Change time" else "Use now"

        val dups = if (mode == "purchase") purchasesOn(ts) else emptyList()
        if (dups.isEmpty()) dupNotice.visibility = View.GONE else {
            dupNotice.visibility = View.VISIBLE
            dupNotice.text = "Already logged " + dayWord(ts) + ": " +
                dups.joinToString(", ") { "${it.qty} at ${timeStr(it.ts)}" } + "."
        }

        if (!priceEdit.hasFocus()) {
            priceEdit.setText(if (price > 0) price.toString().removeSuffix(".0") else "")
        }
        renderHistory()
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        if (entries.isEmpty()) {
            historyBox.addView(tv("Nothing logged yet. Add your first purchase above.", 16f, muted))
            return
        }
        val groups = entries.sortedByDescending { it.ts }.groupBy { dayKey(it.ts) }
        for ((key, items) in groups) {
            historyBox.addView(tv(dayHeading(key, items[0].ts), 16f, ink, true), lp(top = 14))
            for (e in items) {
                historyBox.addView(View(this).apply { setBackgroundColor(line) }, lp(h = dp(1), top = 6))
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(tv(timeStr(e.ts), 15f, muted), LinearLayout.LayoutParams(dp(84), WRAP))
                row.addView(tv(if (e.type == "purchase") "Bought" else "Paid", 16f), lp(0, WRAP, weight = 1f))
                val isBuy = e.type == "purchase"
                row.addView(
                    tv((if (isBuy) "+" else "\u2212") + e.qty, 18f, if (isBuy) owe else paid, true),
                    LinearLayout.LayoutParams(WRAP, WRAP)
                )
                row.addView(tv("Remove", 14f, muted).apply {
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setPadding(dp(14), dp(10), 0, dp(10))
                    setOnClickListener { confirmRemove(e) }
                }, LinearLayout.LayoutParams(WRAP, WRAP))
                historyBox.addView(row, lp())
            }
        }
    }

    // ---------- actions ----------

    private fun readQty(): Int = qtyEdit.text.toString().toIntOrNull() ?: 0

    private fun submit() {
        val qty = readQty()
        if (qty < 1) { setStatus("Enter how many injera.", true); return }
        val useNow = customTs == null
        val ts = customTs ?: System.currentTimeMillis()
        val type = mode
        if (type == "purchase") {
            val dups = purchasesOn(ts)
            if (dups.isNotEmpty()) {
                val lines = dups.joinToString("\n") { "\u2022 ${it.qty} injera at ${timeStr(it.ts)}" }
                AlertDialog.Builder(this)
                    .setTitle("Already logged")
                    .setMessage("You already logged a purchase for ${dayWord(ts)}:\n\n$lines\n\nAdd another $qty?")
                    .setPositiveButton("Log it anyway") { _, _ ->
                        commit(type, qty, if (useNow) System.currentTimeMillis() else ts)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        commit(type, qty, ts)
    }

    private fun commit(type: String, qty: Int, ts: Long) {
        entries.add(Entry(UUID.randomUUID().toString(), type, qty, ts))
        save()
        qtyEdit.setText("1")
        customTs = null
        hideKeyboard()
        render()
        setStatus((if (type == "purchase") "Logged " else "Recorded payment of ") + "$qty injera at ${timeStr(ts)}.")
    }

    private fun confirmRemove(e: Entry) {
        val what = (if (e.type == "purchase") "Bought " else "Paid ") + e.qty + " at " + timeStr(e.ts)
        AlertDialog.Builder(this)
            .setTitle("Remove this entry?")
            .setMessage(what)
            .setPositiveButton("Remove") { _, _ ->
                entries.removeAll { it.id == e.id }
                save(); render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTime() {
        val c = Calendar.getInstance()
        val dlg = DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                val picked = Calendar.getInstance().apply {
                    set(y, m, d, hh, mm, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                customTs = minOf(picked.timeInMillis, System.currentTimeMillis())
                render()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }
}
