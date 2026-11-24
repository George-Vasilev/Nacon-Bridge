package com.vanzetta.gipbridge

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases"

/**
 * Status screen: what is needed, is Shizuku running, is the controller connected, is the
 * virtual gamepad ready, plus a live button test. The engine lives in GipBridgeService.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var service: GipBridgeService? = null
    private var launchedByAttach = false

    private lateinit var headline: TextView
    private lateinit var shizukuRow: StatusRow
    private lateinit var controllerRow: StatusRow
    private lateinit var padRow: StatusRow
    private lateinit var shizukuButton: Button
    private lateinit var sticksText: TextView
    private val buttonViews = LinkedHashMap<String, TextView>()
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logToggle: Button
    private lateinit var setupBody: TextView
    private lateinit var setupHeader: TextView

    @Volatile private var latestInput: GamepadState? = null
    @Volatile private var latestGuide = false
    private var shownInput: Pair<GamepadState?, Boolean>? = null

    private class StatusRow(val dot: TextView, val text: TextView)

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            if (::logScroll.isInitialized && logScroll.visibility == View.VISIBLE) {
                logView.append(line)
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private val statusListener: (BridgeStatus) -> Unit = { s -> runOnUiThread { render(s) } }

    private val inputListener: (GamepadState, Boolean) -> Unit = { state, guide ->
        latestInput = state
        latestGuide = guide
    }

    private val inputRefresher = object : Runnable {
        override fun run() {
            val now = latestInput to latestGuide
            if (now != shownInput) {
                shownInput = now
                renderInput(latestInput, latestGuide)
            }
            handler.postDelayed(this, 50)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as GipBridgeService.LocalBinder).getService()
            service = svc
            svc.setStatusListener(statusListener)
            svc.setInputListener(inputListener)
            svc.setLogListener(logListener)
            if (launchedByAttach) scheduleMoveToBack()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tr.init(this)
        launchedByAttach = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        buildUi()

        val svcIntent = Intent(this, GipBridgeService::class.java)
        // The activity is in the foreground here, so a plain startService is allowed; the
        // service promotes itself to a foreground service in onCreate.
        runCatching { startService(svcIntent) }
            .onFailure { Toast.makeText(this, "Start failed: ${it.message}", Toast.LENGTH_LONG).show() }
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        askNotificationPermission()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            launchedByAttach = true
            runCatching { startService(Intent(this, GipBridgeService::class.java)) }
            if (service != null) scheduleMoveToBack()
        }
    }

    // When Android opened us because a controller was plugged in, get out of the way of the
    // game once everything works. If something needs attention, stay on screen.
    private fun scheduleMoveToBack() {
        launchedByAttach = false
        handler.postDelayed({
            val s = service?.status ?: return@postDelayed
            if (s.shizuku == ShizukuState.READY && s.controller != ControllerState.ERROR &&
                s.controller != ControllerState.USB_PERMISSION_DENIED) {
                moveTaskToBack(true)
            }
        }, 2500)
    }

    override fun onResume() {
        super.onResume()
        handler.post(inputRefresher)
        service?.let { render(it.status) }
    }

    override fun onPause() {
        handler.removeCallbacks(inputRefresher)
        super.onPause()
    }

    override fun onDestroy() {
        service?.setLogListener(null)
        service?.setStatusListener(null)
        service?.setInputListener(null)
        runCatching { unbindService(serviceConnection) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ---- UI -------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0x14888888)
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun statusRow(parent: LinearLayout, label: String): StatusRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val dot = TextView(this).apply { text = "●"; textSize = 20f; setPadding(0, 0, dp(10), 0) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = label; textSize = 13f; alpha = 0.7f })
        val txt = TextView(this).apply { textSize = 16f }
        col.addView(txt)
        row.addView(dot)
        row.addView(col)
        parent.addView(row)
        return StatusRow(dot, txt)
    }

    /** EN | BG switch, top right. The active language is filled, the other one outlined. */
    private fun languageSwitch(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun chip(label: String, isBg: Boolean) = TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val active = Tr.bulgarian == isBg
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (active) setColor(ACCENT) else { setColor(Color.TRANSPARENT); setStroke(dp(1), ACCENT) }
            }
            setTextColor(if (active) Color.WHITE else ACCENT)
            contentDescription = if (isBg) "Български" else "English"
            setOnClickListener {
                if (Tr.bulgarian != isBg) {
                    Tr.set(this@MainActivity, isBg)
                    service?.onLanguageChanged()
                    buildUi()
                    service?.let { s -> render(s.status) }
                }
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(6) }
        }
        box.addView(chip("EN", false))
        box.addView(chip("BG", true))
        return box
    }

    private fun setupText(): String = tr(
        """
        – A USB‑C OTG adapter to plug the controller into the phone.
        – The Shizuku app (free). It is required: without root there is no other way.
        – Start Shizuku with Wireless debugging. This needs Wi‑Fi; Shizuku's own guide shows how.
        – Once Shizuku is running you can switch to mobile data at any time.
        – After every phone restart Shizuku stops. Start it again while on any Wi‑Fi (internet not needed).
        – The first time you plug in: tick “Always open” and tap OK.
        – Works with wired controllers that use the Xbox One / Series protocol (NACON, PDP, PowerA, Hori…). Not Bluetooth, not Xbox 360.
        """.trimIndent(),
        """
        – USB‑C OTG адаптер, за да включиш контролера в телефона.
        – Приложението Shizuku (безплатно). Задължително е: без root няма друг начин.
        – Стартирай Shizuku чрез Wireless debugging (Безжично отстраняване на грешки). Трябва Wi‑Fi; в самия Shizuku има упътване.
        – Щом Shizuku работи, можеш да минеш на мобилни данни по всяко време.
        – След всеки рестарт на телефона Shizuku спира. Стартирай го пак, докато си на някакъв Wi‑Fi (не е нужен интернет).
        – Първия път, когато включиш контролера: сложи отметка „Винаги“ и натисни OK.
        – Работи с жични контролери с Xbox One / Series протокол (NACON, PDP, PowerA, Hori…). Не с Bluetooth и не с Xbox 360.
        """.trimIndent(),
    )

    private fun buildUi() {
        buttonViews.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        // Title row: app name + EN | BG
        val titleRow = FrameLayout(this)
        titleRow.addView(TextView(this).apply {
            text = "NACON Bridge"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        titleRow.addView(languageSwitch(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(titleRow)

        headline = TextView(this).apply { textSize = 16f; setPadding(0, dp(4), 0, dp(14)) }
        root.addView(headline)

        // "Before you start" card (folds itself once the controller has worked)
        val setup = card()
        setupHeader = title("").apply {
            setOnClickListener {
                val collapsed = setupBody.visibility == View.VISIBLE
                DeviceConfig.setSetupCollapsed(this@MainActivity, collapsed)
                applySetupCollapsed(collapsed)
            }
        }
        setup.addView(setupHeader)
        setupBody = TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            text = setupText()
        }
        setup.addView(setupBody)
        setup.addView(Button(this).apply {
            text = tr("Get Shizuku", "Изтегли Shizuku")
            isAllCaps = false
            setOnClickListener { openUrl(SHIZUKU_URL) }
            tag = "setupExtra"
        })
        root.addView(setup)
        applySetupCollapsed(DeviceConfig.setupCollapsed(this))

        // Status card
        val status = card()
        status.addView(title(tr("Status", "Състояние")))
        shizukuRow = statusRow(status, "Shizuku")
        shizukuButton = Button(this).apply { visibility = View.GONE; isAllCaps = false }
        status.addView(shizukuButton)
        controllerRow = statusRow(status, tr("Controller", "Контролер"))
        padRow = statusRow(status, tr("Gamepad for games", "Геймпад за игрите"))
        root.addView(status)

        // Button test card
        val testCard = card()
        testCard.addView(title(tr("Button test", "Тест на бутоните")))
        testCard.addView(TextView(this).apply {
            text = tr("Press a button on the controller – it lights up here.", "Натисни бутон на контролера – той светва тук.")
            textSize = 13f; alpha = 0.7f
            setPadding(0, 0, 0, dp(8))
        })
        val rows = listOf(
            listOf("A", "B", "X", "Y", "Xbox"),
            listOf("LB", "RB", "LT", "RT", "View", "Menu"),
            listOf("↑", "↓", "←", "→", "L3", "R3"),
        )
        for (r in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
            for (name in r) {
                val chip = TextView(this).apply {
                    text = name
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(4)
                    }
                }
                buttonViews[name] = chip
                line.addView(chip)
            }
            testCard.addView(line)
        }
        sticksText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        testCard.addView(sticksText)
        root.addView(testCard)
        shownInput = null
        renderInput(latestInput, latestGuide)

        // Actions
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        actions.addView(actionButton(tr("Test rumble", "Тест вибрация")) {
            val s = service
            if (s == null || s.status.controller == ControllerState.NOT_CONNECTED) {
                Toast.makeText(this, tr("Plug in the controller first", "Първо включи контролера"), Toast.LENGTH_SHORT).show()
            } else s.testRumble()
        })
        logToggle = actionButton(tr("Show log", "Покажи лога")) { toggleLog() }
        actions.addView(logToggle)
        actions.addView(actionButton(tr("Settings", "Настройки")) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(actions)

        // Log (hidden by default)
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(8) }
        }
        root.addView(logScroll)

        // Footer
        root.addView(TextView(this).apply {
            text = madeByLine() + "\n" + tr(
                "Based on GIP Bridge by Christopher VanZetta (MIT). Version ${BuildConfig.VERSION_NAME}.",
                "Базирано на GIP Bridge от Christopher VanZetta (MIT). Версия ${BuildConfig.VERSION_NAME}.",
            )
            textSize = 12f
            alpha = 0.6f
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun applySetupCollapsed(collapsed: Boolean) {
        val label = tr("Before you start", "Преди да започнеш")
        setupHeader.text = if (collapsed) "$label  ▸" else "$label  ▾"
        setupBody.visibility = if (collapsed) View.GONE else View.VISIBLE
        val parent = setupBody.parent as? LinearLayout ?: return
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.tag == "setupExtra") c.visibility = if (collapsed) View.GONE else View.VISIBLE
        }
    }

    private fun toggleLog() {
        if (logScroll.visibility == View.VISIBLE) {
            logScroll.visibility = View.GONE
            logToggle.text = tr("Show log", "Покажи лога")
        } else {
            logView.text = service?.getLogHistory() ?: ""
            logScroll.visibility = View.VISIBLE
            logToggle.text = tr("Hide log", "Скрий лога")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val ACCENT = Color.parseColor("#1E88E5")
    private val GREEN = Color.parseColor("#2E7D32")
    private val RED = Color.parseColor("#C62828")
    private val ORANGE = Color.parseColor("#EF6C00")
    private val GREY = Color.parseColor("#9E9E9E")

    private fun set(row: StatusRow, color: Int, text: String) {
        row.dot.setTextColor(color)
        row.text.text = text
    }

    private fun render(s: BridgeStatus) {
        if (!::headline.isInitialized) return

        // Shizuku
        when (s.shizuku) {
            ShizukuState.READY -> set(shizukuRow, GREEN, tr("Running", "Работи"))
            ShizukuState.CONNECTING -> set(shizukuRow, ORANGE, tr("Connecting...", "Свързване..."))
            ShizukuState.NOT_RUNNING -> set(shizukuRow, RED, tr("Not running. Open Shizuku and tap Start.", "Не е стартиран. Отвори Shizuku и натисни Start."))
            ShizukuState.NO_PERMISSION -> set(shizukuRow, ORANGE, tr("Needs permission. Tap the button and choose “Allow”.", "Трябва разрешение. Натисни бутона и избери „Разреши“."))
            ShizukuState.PERMISSION_DENIED -> set(shizukuRow, RED, tr("Permission denied. Open Shizuku → Authorized applications and turn on NACON Bridge.", "Разрешението е отказано. Отвори Shizuku → Authorized applications и включи NACON Bridge."))
            ShizukuState.UNSUPPORTED -> set(shizukuRow, RED, tr("This Shizuku version is too old. Update Shizuku.", "Тази версия на Shizuku е твърде стара. Обнови Shizuku."))
        }
        when (s.shizuku) {
            ShizukuState.NOT_RUNNING, ShizukuState.PERMISSION_DENIED, ShizukuState.UNSUPPORTED -> {
                shizukuButton.visibility = View.VISIBLE
                val installed = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null
                shizukuButton.text = if (installed) tr("Open Shizuku", "Отвори Shizuku") else tr("Get Shizuku", "Изтегли Shizuku")
                shizukuButton.setOnClickListener { openShizuku() }
            }
            ShizukuState.NO_PERMISSION -> {
                shizukuButton.visibility = View.VISIBLE
                shizukuButton.text = tr("Allow Shizuku", "Разреши Shizuku")
                shizukuButton.setOnClickListener { service?.retryShizuku() }
            }
            else -> shizukuButton.visibility = View.GONE
        }

        // Controller
        val name = s.controllerName ?: tr("The controller", "Контролерът")
        when (s.controller) {
            ControllerState.NOT_CONNECTED -> set(controllerRow, GREY, tr("Not plugged in", "Не е включен"))
            ControllerState.WAITING_USB_PERMISSION -> set(controllerRow, ORANGE, tr("Waiting for USB permission. Tap OK in the window.", "Чакам разрешение за USB. Натисни OK в прозореца."))
            ControllerState.USB_PERMISSION_DENIED -> set(controllerRow, RED, tr("No USB permission. Unplug and plug the controller in again, then tap OK.", "Няма разрешение за USB. Извади и пак включи контролера, после натисни OK."))
            ControllerState.ERROR -> set(controllerRow, RED, tr("Error: ", "Грешка: ") + (s.controllerError ?: "?"))
            ControllerState.NATIVE -> set(controllerRow, GREEN, tr("$name already works on this phone without this app.", "$name работи на този телефон и без това приложение."))
            ControllerState.STARTED -> set(controllerRow, ORANGE, tr("$name found, waking it up...", "$name е открит, събуждам го..."))
            ControllerState.TALKING -> set(controllerRow, ORANGE, tr("$name answers, waiting for buttons...", "$name отговаря, чакам бутоните..."))
            ControllerState.RECEIVING_INPUT -> set(controllerRow, GREEN, tr("$name is working", "$name работи"))
        }

        // Virtual pad
        when {
            s.virtualPadOpen -> set(padRow, GREEN, tr("Ready – games can see the controller", "Готов – игрите виждат контролера"))
            s.controller == ControllerState.NATIVE -> set(padRow, GREY, tr("Not needed", "Не е нужен"))
            s.controller == ControllerState.NOT_CONNECTED -> set(padRow, GREY, tr("Created when you plug in the controller", "Ще се създаде, когато включиш контролера"))
            s.shizuku != ShizukuState.READY -> set(padRow, RED, tr("Needs Shizuku", "Не може без Shizuku"))
            else -> set(padRow, ORANGE, tr("Creating...", "Създаване..."))
        }

        val allGood = s.shizuku == ShizukuState.READY && s.virtualPadOpen && s.controller == ControllerState.RECEIVING_INPUT
        headline.text = when {
            !s.serviceForeground -> tr("Warning: the service did not start properly. See the log.", "Внимание: услугата не можа да стартира правилно. Виж лога.")
            allGood -> tr("All set – you can play.", "Всичко е готово – можеш да играеш.")
            s.controller == ControllerState.NATIVE -> tr("Your controller works without this app.", "Контролерът ти работи и без това приложение.")
            s.shizuku != ShizukuState.READY -> tr("First start Shizuku.", "Първо стартирай Shizuku.")
            s.controller == ControllerState.NOT_CONNECTED -> tr("Plug in the controller.", "Включи контролера.")
            else -> tr("Almost there...", "Почти готово...")
        }

        // Fold the setup card automatically the first time everything works.
        if (allGood && !DeviceConfig.setupCollapsed(this)) {
            DeviceConfig.setSetupCollapsed(this, true)
            applySetupCollapsed(true)
        }
    }

    private fun renderInput(st: GamepadState?, guide: Boolean) {
        fun mark(name: String, on: Boolean) {
            val v = buttonViews[name] ?: return
            v.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (on) GREEN else 0x22888888)
            }
            v.setTextColor(if (on) Color.WHITE else sticksTextColor())
            v.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        val b = st?.buttons ?: 0
        mark("A", b and GipButton.A != 0)
        mark("B", b and GipButton.B != 0)
        mark("X", b and GipButton.X != 0)
        mark("Y", b and GipButton.Y != 0)
        mark("Xbox", guide)
        mark("LB", b and GipButton.BUMPER_L != 0)
        mark("RB", b and GipButton.BUMPER_R != 0)
        mark("LT", (st?.triggerLeft ?: 0) > 100)
        mark("RT", (st?.triggerRight ?: 0) > 100)
        mark("View", b and GipButton.VIEW != 0)
        mark("Menu", b and GipButton.MENU != 0)
        mark("↑", b and GipButton.DPAD_UP != 0)
        mark("↓", b and GipButton.DPAD_DOWN != 0)
        mark("←", b and GipButton.DPAD_LEFT != 0)
        mark("→", b and GipButton.DPAD_RIGHT != 0)
        mark("L3", b and GipButton.STICK_L != 0)
        mark("R3", b and GipButton.STICK_R != 0)

        val ls = tr("Left stick: ", "Ляв стик:  ")
        val rs = tr("Right stick:", "Десен стик:")
        val tg = tr("Triggers:   ", "Тригери:   ")
        if (st == null) {
            sticksText.text = "$ls –\n$rs –\n$tg –"
        } else {
            fun pct(v: Int) = "%+4d%%".format(v * 100 / 32767)
            fun tpct(v: Int) = "%3d%%".format(v * 100 / 1023)
            sticksText.text = "$ls X ${pct(st.stickLeftX)}  Y ${pct(st.stickLeftY)}\n" +
                "$rs X ${pct(st.stickRightX)}  Y ${pct(st.stickRightY)}\n" +
                "$tg LT ${tpct(st.triggerLeft)}  RT ${tpct(st.triggerRight)}"
        }
    }

    private fun sticksTextColor(): Int = if (::sticksText.isInitialized) sticksText.currentTextColor else Color.GRAY

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) startActivity(launch) else openUrl(SHIZUKU_URL)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, url, Toast.LENGTH_LONG).show() }
    }
}
