package com.vanzetta.gipbridge

import android.view.KeyEvent
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

interface RumbleListener {
    fun onRumble(strongPercent: Int, weakPercent: Int, durationMs: Int)
    fun onRumbleStop()
}

/**
 * Runs the native `gipuinputd` daemon as the shell user through Shizuku's process API and
 * talks to it over stdin/stdout (see gipuinputd.c for the line protocol).
 *
 * Why not a Shizuku UserService like the original GIP Bridge: on MediaTek Android 16 ROMs
 * (this Nothing Phone 2a) Shizuku cannot start user services at all: its ServiceStarter dies
 * with a NullPointerException in LoadedApk.makeApplicationInner (Shizuku issue #1198).
 * A plain process does not go through that code.
 */
class ShellInjector(
    private val exePath: String,
    private val log: (String) -> Unit,
    private val onDeath: (ShellInjector) -> Unit,
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    @Volatile var alive = false
        private set
    private val destroyed = AtomicBoolean(false)
    private val deathReported = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val pendingOpen = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val rumble = ConcurrentHashMap<Int, RumbleListener>()

    /** Starts the daemon and waits until it says READY. Blocking, call off the main thread. */
    fun start(): Boolean {
        val p = newShizukuProcess(arrayOf(exePath)) ?: return false
        process = p
        writer = p.outputStream.bufferedWriter()
        alive = true
        thread(name = "gipd-stdout") {
            runCatching { p.inputStream.bufferedReader().forEachLine { handleLine(it) } }
            alive = false
            if (!destroyed.get() && deathReported.compareAndSet(false, true)) {
                log("Injector process stopped.")
                onDeath(this)
            }
        }
        thread(name = "gipd-stderr") {
            runCatching { p.errorStream.bufferedReader().forEachLine { log("injector: $it") } }
        }
        val ok = ready.await(4, TimeUnit.SECONDS) && alive
        if (!ok) log("Injector did not report READY (exe=$exePath)")
        return ok
    }

    private fun handleLine(line: String) {
        when {
            line == "R READY" -> ready.countDown()
            line.startsWith("R O ") -> {
                val parts = line.split(' ')
                val slot = parts.getOrNull(2)?.toIntOrNull() ?: return
                pendingOpen.remove(slot)?.complete(parts.getOrNull(3) == "1")
            }
            line.startsWith("F ") -> {
                val p = line.split(' ')
                if (p.size < 6) return
                val slot = p[1].toIntOrNull() ?: return
                val listener = rumble[slot] ?: return
                val type = p[2].toIntOrNull() ?: return
                val strong = p[3].toIntOrNull() ?: 0
                val weak = p[4].toIntOrNull() ?: 0
                val duration = p[5].toIntOrNull() ?: 0
                runCatching {
                    if (type == 1) listener.onRumble(strong * 100 / 65535, weak * 100 / 65535, if (duration > 0) duration else 200)
                    else listener.onRumbleStop()
                }.onFailure { log("rumble callback failed: ${it.message}") }
            }
            line.startsWith("L ") -> log("injector: ${line.substring(2)}")
            line == "R P" -> Unit
            else -> log("injector says: $line")
        }
    }

    private fun send(line: String): Boolean = synchronized(this) {
        val w = writer ?: return false
        try {
            w.write(line)
            w.write("\n")
            w.flush()
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Creates the uinput gamepad for a player slot. Blocking (up to 3 s). */
    fun openDevice(slot: Int, name: String): Boolean {
        if (!alive) return false
        val future = CompletableFuture<Boolean>()
        pendingOpen[slot] = future
        val cleanName = name.replace(Regex("[\\r\\n]"), " ").take(70)
        if (!send("O $slot $cleanName")) return false
        return runCatching { future.get(3, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    fun closeDevice(slot: Int) {
        rumble.remove(slot)
        send("C $slot")
    }

    fun startRumble(slot: Int, listener: RumbleListener) {
        rumble[slot] = listener
    }

    private val keyMap = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to 0x130,
        KeyEvent.KEYCODE_BUTTON_B to 0x131,
        KeyEvent.KEYCODE_BUTTON_X to 0x133,
        KeyEvent.KEYCODE_BUTTON_Y to 0x134,
        KeyEvent.KEYCODE_BUTTON_L1 to 0x136,
        KeyEvent.KEYCODE_BUTTON_R1 to 0x137,
        KeyEvent.KEYCODE_BUTTON_THUMBL to 0x13d,
        KeyEvent.KEYCODE_BUTTON_THUMBR to 0x13e,
        KeyEvent.KEYCODE_BUTTON_START to 0x13b,
        KeyEvent.KEYCODE_BUTTON_SELECT to 158, // KEY_BACK -> BUTTON_SELECT in Vendor_045e_Product_02fd.kl
        KeyEvent.KEYCODE_BUTTON_MODE to 172,   // KEY_HOMEPAGE -> BUTTON_MODE in the same file
    )

    fun injectKey(slot: Int, keyCode: Int, down: Boolean) {
        val code = keyMap[keyCode]
        if (code != null) {
            send("K $slot $code ${if (down) 1 else 0}")
        } else if (down) {
            // System keys (HOME on a long press of the Xbox button).
            thread(name = "gipd-syskey") {
                runCatching { newShizukuProcess(arrayOf("input", "keyevent", keyCode.toString()))?.waitFor() }
                    .onFailure { log("input keyevent $keyCode failed: ${it.message}") }
            }
        }
    }

    fun injectAxes(slot: Int, x: Float, y: Float, z: Float, rz: Float, ltrigger: Float, rtrigger: Float, hatX: Float, hatY: Float) {
        fun stick(v: Float) = (v * 32767f).toInt().coerceIn(-32768, 32767)
        fun trig(v: Float) = (v * 1023f).toInt().coerceIn(0, 1023)
        // GAS = right trigger, BRAKE = left trigger (RTRIGGER / LTRIGGER in the .kl file).
        send("A $slot ${stick(x)} ${stick(y)} ${stick(z)} ${stick(rz)} ${trig(rtrigger)} ${trig(ltrigger)} " +
            "${hatX.toInt().coerceIn(-1, 1)} ${hatY.toInt().coerceIn(-1, 1)}")
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        alive = false
        send("Q")
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
    }

    companion object {
        /** Shizuku.newProcess is private since API 13 (they want UserService instead), but it
         *  is still served by every Shizuku version and is the only path that works here. */
        fun newShizukuProcess(cmd: Array<String>): Process? {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            )
            m.isAccessible = true
            return m.invoke(null, cmd, null, null) as? Process
        }
    }
}
