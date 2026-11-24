package com.vanzetta.gipbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

private const val TAG = "GipBridge"
private const val ACTION_USB_PERMISSION = "com.user.naconbridge.USB_PERMISSION"
private const val PERMISSION_GRACE_MS = 2000L
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4242
// Xbox button: passed through as BUTTON_MODE; held this long it also sends HOME.
private const val XBOX_LONG_PRESS_MS = 1000L
private const val INPUT_LOG_THROTTLE_MS = 200L
private const val NOTIF_CHANNEL_ID = "nacon_bridge_service"
private const val NOTIF_ID = 1

// GIP interface of an Xbox One / Series wired pad: vendor class 0xFF, subclass 0x47, protocol 0xD0.
private const val GIP_IFACE_CLASS = 0xFF
private const val GIP_IFACE_SUBCLASS = 0x47
private const val GIP_IFACE_PROTOCOL = 0xD0

enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, PERMISSION_DENIED, CONNECTING, READY, UNSUPPORTED }
enum class ControllerState { NOT_CONNECTED, WAITING_USB_PERMISSION, USB_PERMISSION_DENIED, ERROR, NATIVE, STARTED, TALKING, RECEIVING_INPUT }

data class BridgeStatus(
    val serviceForeground: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    val controller: ControllerState = ControllerState.NOT_CONNECTED,
    val controllerName: String? = null,
    val controllerError: String? = null,
    val virtualPadOpen: Boolean = false,
    val packets: Int = 0,
    val inputPackets: Int = 0,
)

/**
 * Owns the USB session with the controller and the Shizuku injector binding, as a foreground
 * service so it keeps running while a game (GeForce NOW etc.) is in front.
 *
 * Flow: a wired Xbox One / Series (GIP) pad is plugged in -> Android launches MainActivity
 * (USB_DEVICE_ATTACHED filter on the GIP interface class) -> this service claims the GIP
 * interface and sends POWER=ON (what makes the pad light up; phone kernels without an xpad
 * entry for the pad never do it) -> reads GIP input packets -> forwards them to a real uinput
 * gamepad created by gipuinputd, which runs as the shell user through Shizuku.
 */
class GipBridgeService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): GipBridgeService = this@GipBridgeService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ---- UI hooks -------------------------------------------------------------------------

    private val logHistory = StringBuilder()
    @Volatile private var logListener: ((String) -> Unit)? = null
    fun setLogListener(listener: ((String) -> Unit)?) { logListener = listener }
    fun getLogHistory(): String = synchronized(logHistory) { logHistory.toString() }

    @Volatile var status = BridgeStatus()
        private set
    @Volatile private var statusListener: ((BridgeStatus) -> Unit)? = null
    fun setStatusListener(listener: ((BridgeStatus) -> Unit)?) {
        statusListener = listener
        listener?.invoke(status)
    }

    /** Latest controller state for the in-app button test (player 1 only). */
    @Volatile private var inputListener: ((GamepadState, Boolean) -> Unit)? = null
    fun setInputListener(listener: ((GamepadState, Boolean) -> Unit)?) { inputListener = listener }
    @Volatile private var guideDown = false

    private val statusLock = Any()
    private fun updateStatus(change: (BridgeStatus) -> BridgeStatus) {
        val old: BridgeStatus
        val new: BridgeStatus
        synchronized(statusLock) {
            old = status
            new = change(old)
            status = new
        }
        if (old != new) {
            statusListener?.invoke(new)
            mainHandler.post { refreshNotification() }
        }
    }

    // ---- USB / sessions -------------------------------------------------------------------

    private lateinit var usbManager: UsbManager

    // Per-controller state, one instance per player slot (index 0 = player 1, 1 = player 2).
    private inner class GipSession(
        val playerIndex: Int,
        val deviceName: String,
        val usbName: String,
        val vid: Int,
        val pid: Int,
    ) {
        var connection: UsbDeviceConnection? = null
        var epOut: UsbEndpoint? = null
        var readerThread: Thread? = null
        var injectorThread: Thread? = null
        @Volatile var running = false
        @Volatile var virtualOpen = false
        val gamepadQueue = java.util.concurrent.ArrayBlockingQueue<GamepadState>(1)
        var lastButtons = 0
        var lastInputLogAtMs = 0L
        var lastSentLX = 0; var lastSentLY = 0
        var lastSentRX = 0; var lastSentRY = 0
        var lastSentLT = 0; var lastSentRT = 0
        var lastSentHatX = 0f; var lastSentHatY = 0f
        var seq = 0
        val writeLock = Any()
        fun nextSeq(): Int { seq = (seq + 1) and 0xFF; if (seq == 0) seq = 1; return seq }
        var xboxLongPressFired = false
        val xboxLongPressRunnable = Runnable {
            xboxLongPressFired = true
            log("XBOX button held (player ${playerIndex + 1}) -> HOME")
            runCatching { injector?.injectKey(playerIndex, KeyEvent.KEYCODE_HOME, true) }
                .onFailure { log("HOME failed: ${it.message}") }
        }
        var lastLoggedRumbleState: Triple<Int, Int, Int>? = null
        var pendingRumbleStop: Runnable? = null
        // Games send rumble as uinput FF effects; GIP rumble packets are one-shot with an
        // explicit duration, so a stop is debounced (150 ms) to keep sustained effects smooth.
        val rumbleCallback = object : RumbleListener {
            override fun onRumble(strongPercent: Int, weakPercent: Int, durationMs: Int) {
                pendingRumbleStop?.let { mainHandler.removeCallbacks(it) }
                pendingRumbleStop = null
                sendRumble(this@GipSession, strongPercent, weakPercent, if (durationMs > 0) durationMs else 200, GipMotor.LEFT or GipMotor.RIGHT)
            }
            override fun onRumbleStop() {
                pendingRumbleStop?.let { mainHandler.removeCallbacks(it) }
                val stop = Runnable {
                    sendRumble(this@GipSession, 0, 0, 0, GipMotor.ALL)
                    pendingRumbleStop = null
                }
                pendingRumbleStop = stop
                mainHandler.postDelayed(stop, 150)
            }
        }
    }

    // Up to 4 controllers at once (gipuinputd has 4 slots). Slot 0 = player 1 = the one the
    // status screen shows.
    private val sessions = arrayOfNulls<GipSession>(4)

    /** Any wired pad speaking GIP (Xbox One / Series protocol), whatever the brand. */
    private fun isGipController(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            val i = device.getInterface(it)
            i.interfaceClass == GIP_IFACE_CLASS && i.interfaceSubclass == GIP_IFACE_SUBCLASS && i.interfaceProtocol == GIP_IFACE_PROTOCOL
        }

    private fun sessionSlotOf(device: UsbDevice): Int = sessions.indexOfFirst { it?.usbName == device.deviceName }

    /** True when the phone's own kernel driver already turned this pad into an input device
     *  (e.g. genuine Microsoft pads on most phones). Then this app stays out of the way. */
    private fun alreadySupportedByPhone(device: UsbDevice): Boolean {
        if (DeviceConfig.forceAll(this)) return false
        return InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            d.vendorId == device.vendorId && d.productId == device.productId && !d.name.contains("GIP Bridge")
        }
    }

    @Volatile private var injector: ShellInjector? = null
    private val injectorStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Shizuku ----------------------------------------------------------------------------

    private fun onInjectorReady(inj: ShellInjector) {
        injector = inj
        log("Shizuku: injector running.")
        updateStatus { it.copy(shizuku = ShizukuState.READY) }
        // Re-arm every running session against the (possibly restarted) injector.
        for (session in sessions) {
            if (session == null) continue
            openVirtualPad(session)
        }
    }

    private fun onInjectorDied(inj: ShellInjector) {
        if (injector !== inj) return
        injector = null
        for (session in sessions) session?.virtualOpen = false
        updateStatus { it.copy(virtualPadOpen = false) }
        refreshShizukuState()
        // Shizuku still alive -> restart the injector after a short pause.
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false) && shizukuPermissionGranted()) {
            mainHandler.postDelayed({ bindInjector() }, 2000)
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted, starting injector...")
            bindInjector()
        } else {
            log("Shizuku permission DENIED.")
        }
        refreshShizukuState()
    }

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener {
        log("Shizuku is running.")
        setupShizuku()
    }

    private val shizukuBinderDead = Shizuku.OnBinderDeadListener {
        log("Shizuku stopped.")
        val old = injector
        injector = null
        runCatching { old?.destroy() }
        for (session in sessions) session?.virtualOpen = false
        updateStatus { it.copy(shizuku = ShizukuState.NOT_RUNNING, virtualPadOpen = false) }
    }

    private fun shizukuPermissionGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun refreshShizukuState() {
        val state = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.NOT_RUNNING
            runCatching { Shizuku.isPreV11() }.getOrDefault(false) -> ShizukuState.UNSUPPORTED
            !shizukuPermissionGranted() ->
                if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false))
                    ShizukuState.PERMISSION_DENIED else ShizukuState.NO_PERMISSION
            injector == null -> ShizukuState.CONNECTING
            else -> ShizukuState.READY
        }
        updateStatus { it.copy(shizuku = state) }
    }

    // Starts gipuinputd (the uinput gamepad daemon) as the shell user via Shizuku. The daemon
    // exits by itself when this app's process dies (its stdin closes), so no orphans remain.
    private fun bindInjector() {
        if (injector?.alive == true) { refreshShizukuState(); return }
        if (!injectorStarting.compareAndSet(false, true)) return
        updateStatus { it.copy(shizuku = ShizukuState.CONNECTING) }
        val exe = applicationInfo.nativeLibraryDir + "/libgipuinputd.so"
        thread(name = "gipd-start") {
            val inj = ShellInjector(exe, ::log) { dead -> mainHandler.post { onInjectorDied(dead) } }
            val ok = runCatching { inj.start() }
                .onFailure { log("Could not start the injector: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            injectorStarting.set(false)
            mainHandler.post {
                if (ok) onInjectorReady(inj)
                else {
                    inj.destroy()
                    refreshShizukuState()
                    if (status.shizuku == ShizukuState.CONNECTING) {
                        updateStatus { it.copy(shizuku = ShizukuState.CONNECTING) }
                        mainHandler.postDelayed({ bindInjector() }, 5000)
                    }
                }
            }
        }
    }

    private fun setupShizuku() {
        runCatching {
            if (Shizuku.isPreV11()) { log("Shizuku is too old (pre-v11)."); refreshShizukuState(); return }
            when {
                shizukuPermissionGranted() -> bindInjector()
                Shizuku.shouldShowRequestPermissionRationale() ->
                    log("Shizuku permission was denied before. Allow it in the Shizuku app.")
                else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        }.onFailure { log("Shizuku setup failed: ${it.message}") }
        refreshShizukuState()
    }

    /** Called from the UI ("Разреши Shizuku" / "Опитай пак"). */
    fun retryShizuku() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            log("Shizuku is not running. Start it in the Shizuku app.")
            refreshShizukuState()
            return
        }
        if (injector == null) setupShizuku()
        refreshShizukuState()
    }

    private fun openVirtualPad(session: GipSession) {
        val inj = injector ?: return
        thread(name = "gipd-open") {
            val ok = runCatching { inj.openDevice(session.playerIndex, session.deviceName) }
                .onFailure { log("openDevice failed (player ${session.playerIndex + 1}): ${it.message}") }
                .getOrDefault(false)
            if (!session.running) {
                // Controller was unplugged meanwhile.
                if (ok) runCatching { inj.closeDevice(session.playerIndex) }
                return@thread
            }
            session.virtualOpen = ok
            log(if (ok) "Virtual gamepad ready (player ${session.playerIndex + 1})."
                else "ERROR: could not create the virtual gamepad (player ${session.playerIndex + 1}).")
            if (ok) inj.startRumble(session.playerIndex, session.rumbleCallback)
            if (session.playerIndex == 0) updateStatus { it.copy(virtualPadOpen = ok) }
        }
    }

    // ---- USB receivers ----------------------------------------------------------------------

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                log("USB permission granted for ${device.deviceName}")
                if (isGipController(device)) startGipSession(device)
            } else {
                log("USB permission DENIED")
                updateStatus { it.copy(controller = ControllerState.USB_PERMISSION_DENIED) }
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            if (device != null && isGipController(device)) {
                log("Attach event: ${device.manufacturerName} ${device.productName} (vid=${device.vendorId} pid=${device.productId})")
                requestPermissionAndConnect(device)
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE) ?: return
            val slot = sessionSlotOf(device)
            if (slot >= 0) {
                log("Controller (player ${slot + 1}) unplugged.")
                stopGipSession(slot)
            }
        }
    }

    // ---- Lifecycle --------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Tr.init(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        createNotificationChannel()
        startInForeground()

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, attachFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbDetachReceiver, detachFilter)
        }

        // None of these throw when Shizuku is not running; the binder listener simply fires
        // later, when the user starts Shizuku.
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderDeadListener(shizukuBinderDead)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        }.onFailure { log("Shizuku listener setup failed: ${it.message}") }
        refreshShizukuState()

        log(
            "NACON Bridge ${BuildConfig.VERSION_NAME} started on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}. Looking for wired Xbox One/Series (GIP) controllers..."
        )
        findAndConnectExisting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-scan on every start request (e.g. the activity was relaunched by a USB attach).
        findAndConnectExisting()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, "NACON Bridge", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notificationText(s: BridgeStatus): Pair<String, String> = when {
        s.shizuku == ShizukuState.NOT_RUNNING ->
            tr("Start Shizuku", "Стартирай Shizuku") to
                tr("Games can't see the controller without Shizuku. Open Shizuku and tap Start.",
                    "Без Shizuku игрите не виждат контролера. Отвори Shizuku и натисни Start.")
        s.shizuku == ShizukuState.NO_PERMISSION || s.shizuku == ShizukuState.PERMISSION_DENIED ->
            tr("Allow NACON Bridge in Shizuku", "Разреши NACON Bridge в Shizuku") to
                tr("Open the app and tap “Allow Shizuku”.", "Отвори приложението и натисни „Разреши Shizuku“.")
        s.controller == ControllerState.RECEIVING_INPUT && s.virtualPadOpen ->
            tr("Controller is working", "Контролерът работи") to tr("You can play.", "Можеш да играеш.")
        s.controller == ControllerState.STARTED || s.controller == ControllerState.TALKING ->
            tr("Controller connected", "Контролерът е свързан") to
                if (s.virtualPadOpen) tr("Press a button to check.", "Натисни бутон, за да провериш.")
                else tr("Waiting for Shizuku...", "Изчаквам Shizuku...")
        s.controller == ControllerState.NOT_CONNECTED ->
            tr("Plug in the controller", "Включи контролера") to tr("Waiting for a controller.", "Чакам контролер.")
        s.controller == ControllerState.WAITING_USB_PERMISSION ->
            tr("Allow access to the controller", "Разреши достъп до контролера") to
                tr("Tap OK in the USB window.", "Натисни OK в прозореца за USB.")
        s.controller == ControllerState.USB_PERMISSION_DENIED ->
            tr("No access to the controller", "Няма достъп до контролера") to
                tr("Unplug and plug the controller in again, then tap OK.", "Извади и пак включи контролера, после натисни OK.")
        else -> tr("Controller problem", "Проблем с контролера") to
            (s.controllerError ?: tr("Open the app for details.", "Отвори приложението за подробности."))
    }

    /** Called by the UI after the EN / BG switch. */
    fun onLanguageChanged() {
        lastNotifText = null
        refreshNotification()
    }

    private var lastNotifText: Pair<String, String>? = null

    private fun buildNotification(s: BridgeStatus): android.app.Notification {
        val (title, text) = notificationText(s)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification(status)
        lastNotifText = notificationText(status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            status = status.copy(serviceForeground = true)
        } catch (e: Exception) {
            // Never crash the app over this: keep running as a normal service while the app is
            // open, and show the reason on screen.
            Log.e(TAG, "startForeground failed", e)
            log("WARNING: could not start as a foreground service: ${e.javaClass.simpleName}: ${e.message}")
            status = status.copy(serviceForeground = false)
        }
    }

    private fun refreshNotification() {
        if (!status.serviceForeground) return
        val text = notificationText(status)
        if (text == lastNotifText) return
        lastNotifText = text
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status)) }
    }

    private fun findAndConnectExisting() {
        if (!::usbManager.isInitialized) return
        val targets = usbManager.deviceList.values.filter { isGipController(it) }
        if (targets.isEmpty()) {
            if (sessions.all { it == null }) {
                log("No controller plugged in yet.")
                updateStatus { it.copy(controller = ControllerState.NOT_CONNECTED, controllerName = null, controllerError = null) }
            }
            return
        }
        for (target in targets) {
            if (sessionSlotOf(target) >= 0) continue
            log("Found controller: ${target.manufacturerName} ${target.productName} (${target.deviceName})")
            requestPermissionAndConnect(target)
        }
    }

    private fun requestPermissionAndConnect(device: UsbDevice) {
        if (!isGipController(device) || sessionSlotOf(device) >= 0) return
        if (usbManager.hasPermission(device)) {
            // Give the phone's own driver a moment to register the pad (if it has one), so
            // alreadySupportedByPhone() sees it.
            mainHandler.postDelayed({ startGipSession(device) }, 600)
            return
        }
        if (sessions[0] == null) updateStatus { it.copy(controller = ControllerState.WAITING_USB_PERMISSION, controllerName = device.productName) }
        // On a real attach the system grants permission when it launches MainActivity for the
        // saved default ("Always open"), but this can run first. Give that grant a moment.
        mainHandler.postDelayed({ requestPermissionNow(device) }, PERMISSION_GRACE_MS)
    }

    // Avoids stacking several USB permission dialogs (onCreate + onStartCommand + attach event).
    private var lastPermissionRequestAt = 0L

    private fun requestPermissionNow(device: UsbDevice) {
        if (sessionSlotOf(device) >= 0) return
        if (usbManager.hasPermission(device)) {
            startGipSession(device)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPermissionRequestAt < 5000) return
        lastPermissionRequestAt = now
        // Android 14+ (targetSdk 34) refuses a mutable PendingIntent around an implicit Intent,
        // so the intent must name our package explicitly. FLAG_MUTABLE is required here: the
        // system fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        runCatching { usbManager.requestPermission(device, pi) }
            .onSuccess { log("Asked for USB permission, waiting for OK...") }
            .onFailure { log("USB permission request failed: ${it.message}") }
    }

    private val KNOWN_MODELS = mapOf(
        (0x3285 to 0x0646) to "NACON Pro Compact",
        (0x2dc8 to 0x2015) to "8BitDo Ultimate Wired",
        (0x2dc8 to 0x2000) to "8BitDo Pro 2 Wired",
    )

    private fun modelName(device: UsbDevice): String {
        KNOWN_MODELS[device.vendorId to device.productId]?.let { return it }
        val maker = device.manufacturerName?.trim().orEmpty()
        val product = device.productName?.trim().orEmpty()
        return when {
            product.isEmpty() -> "Controller %04x:%04x".format(device.vendorId, device.productId)
            maker.isEmpty() || product.startsWith(maker, ignoreCase = true) -> product
            else -> "$maker $product"
        }
    }

    // Must NOT contain "Virtual" (RetroArch relabels such devices).
    private fun padNameFor(device: UsbDevice, playerIndex: Int): String {
        val model = modelName(device).replace("Virtual", "").trim()
        return if (playerIndex == 0) "$model (GIP Bridge)" else "$model (GIP Bridge P${playerIndex + 1})"
    }

    private fun findGipInterface(device: UsbDevice): UsbInterface? {
        val all = (0 until device.interfaceCount).map { device.getInterface(it) }
        for (i in all) {
            log("  interface id=${i.id} alt=${i.alternateSetting} class=${i.interfaceClass} sub=${i.interfaceSubclass} proto=${i.interfaceProtocol} endpoints=${i.endpointCount}")
        }
        return all.firstOrNull {
            it.interfaceClass == GIP_IFACE_CLASS && it.interfaceSubclass == GIP_IFACE_SUBCLASS &&
                it.interfaceProtocol == GIP_IFACE_PROTOCOL && it.endpointCount >= 2
        } ?: all.firstOrNull { it.id == 0 && it.endpointCount >= 2 }
    }

    @Synchronized
    private fun startGipSession(device: UsbDevice) {
        if (sessionSlotOf(device) >= 0) return
        if (alreadySupportedByPhone(device)) {
            log("${modelName(device)} already works through the phone's own driver. Leaving it alone.")
            if (sessions[0] == null) {
                updateStatus { it.copy(controller = ControllerState.NATIVE, controllerName = modelName(device), controllerError = null) }
            }
            return
        }
        val playerIndex = sessions.indexOfFirst { it == null }
        if (playerIndex < 0) { log("Already 4 controllers connected, ignoring ${device.productName}."); return }
        val session = GipSession(playerIndex, padNameFor(device, playerIndex), device.deviceName, device.vendorId, device.productId)
        fun fail(msg: String) {
            log("ERROR: $msg")
            if (playerIndex == 0) updateStatus { it.copy(controller = ControllerState.ERROR, controllerError = msg) }
        }

        log("Connecting to ${device.productName} (vid=${device.vendorId} pid=${device.productId}), ${device.interfaceCount} interfaces:")
        val iface = findGipInterface(device) ?: return fail("GIP interface not found")

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
        }
        val inEp: UsbEndpoint = epIn ?: return fail("IN endpoint not found on interface ${iface.id}")
        val outEp: UsbEndpoint = epOut ?: return fail("OUT endpoint not found on interface ${iface.id}")

        val connection: UsbDeviceConnection = usbManager.openDevice(device) ?: return fail("openDevice failed")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return fail("claimInterface failed")
        }

        // xone's wired driver re-asserts interface 1 (audio) alt-setting 0, "mandatory for
        // certain third party devices". Harmless when the interface is missing.
        if (DeviceConfig.resetAudioIface(this)) {
            val audioIface: UsbInterface? = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.id == 1 && it.alternateSetting == 0 }
            if (audioIface != null) {
                val claimed = connection.claimInterface(audioIface, true)
                val setOk = if (claimed) connection.setInterface(audioIface) else false
                log("Interface 1 alt=0: claim=$claimed setInterface=$setOk")
            }
        }

        log("Interface ${iface.id} claimed (player ${playerIndex + 1}). IN=0x${inEp.address.toString(16)} OUT=0x${outEp.address.toString(16)} maxPacket=${inEp.maxPacketSize}")
        session.connection = connection
        session.epOut = outEp
        session.running = true
        sessions[playerIndex] = session
        if (playerIndex == 0) {
            updateStatus {
                it.copy(controller = ControllerState.STARTED, controllerName = modelName(device),
                    controllerError = null, packets = 0, inputPackets = 0)
            }
        }

        // What Linux xpad does: send the start-up packets (POWER=ON plus a few brand-specific
        // ones) straight away instead of waiting for ANNOUNCE. A controller that already
        // announced (e.g. the app restarted while it stayed plugged in) never announces again,
        // so this is also what wakes it up in that case.
        if (DeviceConfig.earlyPowerOn(this)) {
            sendInitPackets(session, connection, outEp)
            if (DeviceConfig.ledOn(this)) sendLedOn(session, connection, outEp)
        }

        openVirtualPad(session)

        session.readerThread = thread(name = "gip-reader-p$playerIndex") { readLoop(session, connection, inEp, outEp) }
        session.injectorThread = thread(name = "gip-injector-p$playerIndex") { gipInjectLoop(session) }
    }

    @Synchronized
    private fun stopGipSession(playerIndex: Int) {
        val session = sessions[playerIndex] ?: return
        session.running = false
        mainHandler.removeCallbacks(session.xboxLongPressRunnable)
        session.readerThread?.join(500)
        session.injectorThread?.join(500)
        runCatching { session.connection?.close() }
        runCatching { injector?.closeDevice(playerIndex) }
        sessions[playerIndex] = null
        if (playerIndex == 0) {
            updateStatus { it.copy(controller = ControllerState.NOT_CONNECTED, controllerName = null, virtualPadOpen = false, controllerError = null) }
        }
    }

    // Start-up packets from Linux xpad (drivers/input/joystick/xpad.c, xboxone_init_packets),
    // sent in the same order. Byte 2 (sequence) is filled in when sending. Vendor/product 0
    // means "any". Every pad gets POWER=ON; some brands need one extra packet to start
    // reporting input.
    private class InitPacket(val vid: Int, val pid: Int, val data: ByteArray)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val INIT_PACKETS by lazy {
        val horiAckId = bytes(0x01, 0x20, 0x00, 0x09, 0x00, 0x04, 0x20, 0x3a, 0x00, 0x00, 0x00, 0x80, 0x00)
        val powerOn = bytes(0x05, 0x20, 0x00, 0x01, 0x00)
        val sInit = bytes(0x05, 0x20, 0x00, 0x0f, 0x06)
        val extraInput = bytes(0x4d, 0x10, 0x01, 0x02, 0x07, 0x00)
        val pdpLedOn = bytes(0x0a, 0x20, 0x00, 0x03, 0x00, 0x01, 0x14)
        val pdpAuth = bytes(0x06, 0x20, 0x00, 0x02, 0x01, 0x00)
        val rumbleBegin = bytes(0x09, 0x00, 0x00, 0x09, 0x00, 0x0f, 0x00, 0x00, 0x1d, 0x1d, 0xff, 0x00, 0x00)
        val rumbleEnd = bytes(0x09, 0x00, 0x00, 0x09, 0x00, 0x0f, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        listOf(
            InitPacket(0x0e6f, 0x0165, horiAckId),   // Hori Fighting Commander ONE
            InitPacket(0x0f0d, 0x0067, horiAckId),   // Hori
            InitPacket(0, 0, powerOn),               // everyone
            InitPacket(0x045e, 0x02ea, sInit),       // Microsoft Xbox One S
            InitPacket(0x045e, 0x0b00, sInit),       // Microsoft Elite Series 2
            InitPacket(0x045e, 0x0b00, extraInput),
            InitPacket(0x0e6f, 0, pdpLedOn),         // PDP
            InitPacket(0x0e6f, 0, pdpAuth),
            InitPacket(0x24c6, 0x541a, rumbleBegin), // PowerA
            InitPacket(0x24c6, 0x542a, rumbleBegin),
            InitPacket(0x24c6, 0x543a, rumbleBegin),
            InitPacket(0x24c6, 0x541a, rumbleEnd),
            InitPacket(0x24c6, 0x542a, rumbleEnd),
            InitPacket(0x24c6, 0x543a, rumbleEnd),
        )
    }

    private fun sendInitPackets(session: GipSession, conn: UsbDeviceConnection, epOut: UsbEndpoint) {
        for (p in INIT_PACKETS) {
            if (p.vid != 0 && p.vid != session.vid) continue
            if (p.pid != 0 && p.pid != session.pid) continue
            val out = p.data.copyOf()
            out[2] = session.nextSeq().toByte()
            val n = synchronized(session.writeLock) { conn.bulkTransfer(epOut, out, out.size, 1000) }
            log("Sent init packet ${out.toHex()} (player ${session.playerIndex + 1})${if (n < 0) " - FAILED" else ""}")
        }
    }

    private fun gipInjectLoop(session: GipSession) {
        while (session.running) {
            val state = runCatching {
                session.gamepadQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrNull() ?: continue
            runCatching { injectGamepadState(session, state) }
                .onFailure { log("inject failed (player ${session.playerIndex + 1}): ${it.message}") }
        }
    }

    // Every iteration is wrapped: an uncaught exception on any thread kills the whole app.
    private fun readLoop(session: GipSession, conn: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint) {
        val buf = ByteArray(256)
        var packetsSeen = 0
        var inputPacketsSeen = 0
        var silentSince = SystemClock.elapsedRealtime()
        var silentRetries = 0
        val pi = session.playerIndex
        while (session.running) {
          try {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 1000)
            if (n <= 0) {
                // Nothing heard yet: re-send POWER=ON a few times (some pads miss the first
                // one while they are still booting).
                if (packetsSeen == 0 && SystemClock.elapsedRealtime() - silentSince > 1500 && silentRetries < 5) {
                    silentRetries++
                    log("No reply from the controller yet, sending POWER=ON again ($silentRetries/5)")
                    sendPowerOn(session, conn, epOut)
                    if (DeviceConfig.ledOn(this)) sendLedOn(session, conn, epOut)
                    silentSince = SystemClock.elapsedRealtime()
                }
                Thread.sleep(20)
                continue
            }

            packetsSeen++
            if (packetsSeen <= 15) log("RX #$packetsSeen (${n}B): ${buf.copyOf(n).toHex()}")

            val data = buf.copyOf(n)
            val (hdr, hdrLen) = try {
                decodeGipHeader(data)
            } catch (e: Exception) {
                log("decode error on ${n}B packet (player ${pi + 1}): ${e.message} raw=${data.toHex()}")
                continue
            }
            val payload = if (data.size > hdrLen) data.copyOfRange(hdrLen, data.size) else ByteArray(0)
            if (pi == 0 && (packetsSeen == 1 || packetsSeen % 200 == 0)) {
                updateStatus {
                    it.copy(controller = if (it.controller == ControllerState.STARTED) ControllerState.TALKING else it.controller,
                        packets = packetsSeen)
                }
            }

            when {
                hdr.isInternal && hdr.command == GipCommand.ANNOUNCE -> {
                    val ann = parseAnnounce(payload)
                    log("ANNOUNCE (player ${pi + 1}): vid=${ann?.vendorId} pid=${ann?.productId} fw=${ann?.fwMajor}.${ann?.fwMinor}.${ann?.fwBuild}.${ann?.fwRevision} needsAck=${hdr.needsAck}")
                    if (hdr.needsAck) sendAck(session, conn, epOut, hdr)
                    sendPowerOn(session, conn, epOut)
                    if (DeviceConfig.ledOn(this)) sendLedOn(session, conn, epOut)
                    if (DeviceConfig.sendIdentify(this)) sendIdentifyRequest(session, conn, epOut)
                }
                hdr.isInternal && hdr.command == GipCommand.STATUS -> {
                    log("STATUS (player ${pi + 1}): ${payload.toHex()}")
                    if (hdr.needsAck) sendAck(session, conn, epOut, hdr)
                }
                hdr.isInternal && hdr.command == GipCommand.VIRTUAL_KEY -> {
                    // The Xbox button has its own report and must be acknowledged, or the pad
                    // keeps resending it and stops reporting it.
                    if (hdr.needsAck) sendAck(session, conn, epOut, hdr)
                    if (payload.size >= 2) {
                        val down = payload[0].toInt() != 0
                        val key = payload[1].toInt() and 0xFF
                        if (key == 0x5b) handleXboxButton(session, down)
                        else log("virtual key 0x${key.toString(16)} (player ${pi + 1}): ${if (down) "DOWN" else "UP"}")
                    }
                }
                hdr.isInternal -> {
                    log("internal cmd=0x${hdr.command.toString(16)} opts=0x${hdr.options.toString(16)} len=${payload.size} raw=${payload.toHex()} (player ${pi + 1})")
                    if (hdr.needsAck) sendAck(session, conn, epOut, hdr)
                }
                hdr.command == GipCommand.INPUT -> {
                    inputPacketsSeen++
                    val state = parseGamepadInput(payload)
                    if (pi == 0 && (inputPacketsSeen == 1 || inputPacketsSeen % 200 == 0)) {
                        updateStatus { it.copy(controller = ControllerState.RECEIVING_INPUT, inputPackets = inputPacketsSeen) }
                    }
                    if (state != null && state.describe() != "idle") {
                        val now = SystemClock.elapsedRealtime()
                        if (now - session.lastInputLogAtMs >= INPUT_LOG_THROTTLE_MS) {
                            log("INPUT (player ${pi + 1}): ${state.describe()}")
                            session.lastInputLogAtMs = now
                        }
                    } else if (inputPacketsSeen <= 2) {
                        log("INPUT #$inputPacketsSeen idle (${payload.size}B) (player ${pi + 1})")
                    }
                    if (state != null) {
                        if (pi == 0) inputListener?.invoke(state, guideDown)
                        session.gamepadQueue.poll() // keep only the latest state
                        session.gamepadQueue.offer(state)
                    }
                    if (hdr.needsAck) sendAck(session, conn, epOut, hdr)
                }
                else -> {
                    log("unhandled cmd=0x${hdr.command.toString(16)} opts=0x${hdr.options.toString(16)} raw=${payload.toHex()} (player ${pi + 1})")
                    if (hdr.needsAck) sendAck(session, conn, epOut, hdr)
                }
            }
          } catch (e: Throwable) {
            log("readLoop iteration failed (player ${pi + 1}): ${e.message}")
            runCatching { Thread.sleep(50) }
          }
        }
        log("Read loop stopped after $packetsSeen packets ($inputPacketsSeen INPUT) (player ${pi + 1}).")
    }

    private fun handleXboxButton(session: GipSession, down: Boolean) {
        val pi = session.playerIndex
        log("XBOX button (player ${pi + 1}): ${if (down) "DOWN" else "UP"}")
        if (pi == 0) {
            guideDown = down
            val last = lastUiState
            if (last != null) inputListener?.invoke(last, down)
        }
        runCatching { injector?.injectKey(pi, KeyEvent.KEYCODE_BUTTON_MODE, down) }
            .onFailure { log("inject XBOX button failed (player ${pi + 1}): ${it.message}") }
        if (down) {
            session.xboxLongPressFired = false
            mainHandler.postDelayed(session.xboxLongPressRunnable, XBOX_LONG_PRESS_MS)
        } else {
            mainHandler.removeCallbacks(session.xboxLongPressRunnable)
        }
    }

    @Volatile private var lastUiState: GamepadState? = null

    // Sticks/triggers are only sent when they really moved (deadzones in raw units), so an idle
    // controller does not spam cross-process calls.
    private val STICK_DEADZONE = 300
    private val TRIGGER_DEADZONE = 15

    private fun injectGamepadState(session: GipSession, state: GamepadState) {
        if (session.playerIndex == 0) lastUiState = state
        val inj = injector ?: return
        if (!session.virtualOpen) return
        val pi = session.playerIndex

        val changed = state.buttons xor session.lastButtons
        if (changed != 0) {
            fun key(mask: Int, code: Int) {
                if (changed and mask != 0) inj.injectKey(pi, code, state.buttons and mask != 0)
            }
            key(GipButton.A, KeyEvent.KEYCODE_BUTTON_A)
            key(GipButton.B, KeyEvent.KEYCODE_BUTTON_B)
            key(GipButton.X, KeyEvent.KEYCODE_BUTTON_X)
            key(GipButton.Y, KeyEvent.KEYCODE_BUTTON_Y)
            key(GipButton.BUMPER_L, KeyEvent.KEYCODE_BUTTON_L1)
            key(GipButton.BUMPER_R, KeyEvent.KEYCODE_BUTTON_R1)
            key(GipButton.STICK_L, KeyEvent.KEYCODE_BUTTON_THUMBL)
            key(GipButton.STICK_R, KeyEvent.KEYCODE_BUTTON_THUMBR)
            key(GipButton.MENU, KeyEvent.KEYCODE_BUTTON_START)
            key(GipButton.VIEW, KeyEvent.KEYCODE_BUTTON_SELECT)
            session.lastButtons = state.buttons
        }

        val hatX = when {
            state.buttons and GipButton.DPAD_LEFT != 0 -> -1f
            state.buttons and GipButton.DPAD_RIGHT != 0 -> 1f
            else -> 0f
        }
        val hatY = when {
            state.buttons and GipButton.DPAD_UP != 0 -> -1f
            state.buttons and GipButton.DPAD_DOWN != 0 -> 1f
            else -> 0f
        }

        fun moved(a: Int, b: Int, deadzone: Int) = kotlin.math.abs(a - b) > deadzone
        val axesChanged = hatX != session.lastSentHatX || hatY != session.lastSentHatY ||
            moved(state.stickLeftX, session.lastSentLX, STICK_DEADZONE) || moved(state.stickLeftY, session.lastSentLY, STICK_DEADZONE) ||
            moved(state.stickRightX, session.lastSentRX, STICK_DEADZONE) || moved(state.stickRightY, session.lastSentRY, STICK_DEADZONE) ||
            moved(state.triggerLeft, session.lastSentLT, TRIGGER_DEADZONE) || moved(state.triggerRight, session.lastSentRT, TRIGGER_DEADZONE) ||
            // always deliver exact zero / full-press so a released stick or trigger never sticks
            (state.triggerLeft == 0 && session.lastSentLT != 0) || (state.triggerRight == 0 && session.lastSentRT != 0)

        if (axesChanged) {
            inj.injectAxes(
                pi,
                state.stickLeftX / 32767f,
                -state.stickLeftY / 32767f, // GIP Y is up-positive, Android Y is down-positive
                state.stickRightX / 32767f,
                -state.stickRightY / 32767f,
                state.triggerLeft / 1023f,
                state.triggerRight / 1023f,
                hatX,
                hatY,
            )
            session.lastSentLX = state.stickLeftX; session.lastSentLY = state.stickLeftY
            session.lastSentRX = state.stickRightX; session.lastSentRY = state.stickRightY
            session.lastSentLT = state.triggerLeft; session.lastSentRT = state.triggerRight
            session.lastSentHatX = hatX; session.lastSentHatY = hatY
        }
    }

    // ---- GIP output -------------------------------------------------------------------------

    private fun sendAck(session: GipSession, conn: UsbDeviceConnection, epOut: UsbEndpoint, acked: GipHeader) {
        val payload = buildAcknowledgePayload(acked.command, clientId = acked.options and GipOption.CLIENT_ID_MASK, totalLen = acked.packetLength)
        val hdr = GipHeader(
            command = GipCommand.ACKNOWLEDGE,
            options = GipOption.INTERNAL,
            sequence = acked.sequence,
            packetLength = payload.size,
        )
        writePacket(session, conn, epOut, hdr, payload)
    }

    private fun sendPowerOn(session: GipSession, conn: UsbDeviceConnection, epOut: UsbEndpoint) {
        val payload = byteArrayOf(0x00) // GIP_PWR_ON
        val hdr = GipHeader(GipCommand.POWER, GipOption.INTERNAL, session.nextSeq(), payload.size)
        val ok = writePacket(session, conn, epOut, hdr, payload)
        log("Sent POWER=ON (player ${session.playerIndex + 1})${if (ok) "" else " - FAILED"}")
    }

    private fun sendLedOn(session: GipSession, conn: UsbDeviceConnection, epOut: UsbEndpoint) {
        val payload = byteArrayOf(0x00, 0x01, 0x14) // GIP_LED_ON, brightness 20
        val hdr = GipHeader(GipCommand.LED, GipOption.INTERNAL, session.nextSeq(), payload.size)
        writePacket(session, conn, epOut, hdr, payload)
    }

    private fun sendIdentifyRequest(session: GipSession, conn: UsbDeviceConnection, epOut: UsbEndpoint) {
        val hdr = GipHeader(GipCommand.IDENTIFY, GipOption.INTERNAL, session.nextSeq(), 0)
        writePacket(session, conn, epOut, hdr, ByteArray(0))
        log("Sent IDENTIFY request (player ${session.playerIndex + 1})")
    }

    /** Player 1 rumble test from the app: big motor, small motor, triggers, all. */
    fun testRumble() {
        val session = sessions[0] ?: run { log("Rumble test: controller not connected"); return }
        log("Rumble test...")
        thread(name = "test-rumble") {
            fun pulse(label: String, motors: Int) {
                log("Rumble test: $label")
                sendRumble(session, 100, 100, 600, motors, ignoreSettings = true)
                Thread.sleep(700)
                sendRumble(session, 0, 0, 0, motors, ignoreSettings = true)
                Thread.sleep(300)
            }
            pulse("left (big) motor", GipMotor.LEFT)
            pulse("right (small) motor", GipMotor.RIGHT)
            pulse("trigger motors", GipMotor.TRIGGER_LEFT or GipMotor.TRIGGER_RIGHT)
        }
    }

    // strongPercent/weakPercent are 0-100, scaled by the user's rumble strength.
    // durationMs=0 sends an explicit stop.
    private fun sendRumble(
        session: GipSession, strongPercent: Int, weakPercent: Int, durationMs: Int,
        motors: Int = GipMotor.ALL, ignoreSettings: Boolean = false,
    ) {
        val conn = session.connection ?: return
        val epOut = session.epOut ?: return
        if (!ignoreSettings && durationMs > 0 && !DeviceConfig.rumbleEnabled(this)) return

        val userStrength = if (ignoreSettings) 100 else DeviceConfig.rumbleStrength(this)
        val left = (strongPercent * userStrength / 100).coerceIn(0, 100)
        val right = (weakPercent * userStrength / 100).coerceIn(0, 100)
        val durationTens = (durationMs / 10).coerceIn(0, 255)

        val payload = buildRumblePayload(motors = motors, leftTrigger = left, rightTrigger = right, left = left, right = right, durationTens = durationTens)
        val rState = Triple(motors, left, right)
        if (rState != session.lastLoggedRumbleState) {
            log("rumble (player ${session.playerIndex + 1}): motors=0x${motors.toString(16)} left=$left right=$right")
            session.lastLoggedRumbleState = rState
        }
        val hdr = GipHeader(
            command = GipCommand.RUMBLE,
            options = 0, // client command: no INTERNAL flag
            sequence = session.nextSeq(),
            packetLength = payload.size,
        )
        writePacket(session, conn, epOut, hdr, payload)
    }

    private fun writePacket(session: GipSession, conn: UsbDeviceConnection, epOut: UsbEndpoint, hdr: GipHeader, payload: ByteArray): Boolean {
        val out = encodeGipHeader(hdr) + payload
        val n = synchronized(session.writeLock) { conn.bulkTransfer(epOut, out, out.size, 1000) }
        if (n < 0) log("WARN: USB write failed for cmd=0x${hdr.command.toString(16)} (${out.toHex()})")
        return n >= 0
    }

    private fun log(msg: String) {
        Log.i(TAG, msg) // INFO: Nothing OS drops DEBUG-level logs from apps

        val line = "$msg\n"
        synchronized(logHistory) {
            logHistory.append(line)
            if (logHistory.length > 30000) logHistory.delete(0, logHistory.length - 30000)
        }
        logListener?.invoke(line)
    }

    override fun onDestroy() {
        for (i in sessions.indices) stopGipSession(i)
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(usbAttachReceiver) }
        runCatching { unregisterReceiver(usbDetachReceiver) }
        val inj = injector
        injector = null
        runCatching { inj?.destroy() }
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        runCatching { Shizuku.removeBinderReceivedListener(shizukuBinderReceived) }
        runCatching { Shizuku.removeBinderDeadListener(shizukuBinderDead) }
        super.onDestroy()
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key)
    }
}
