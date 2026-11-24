package com.vanzetta.gipbridge

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings: rumble, Shizuku help, connected USB devices (for bug reports) and the GIP
 * handshake quirks. Handshake changes apply the next time the controller is plugged in.
 */
class SettingsActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var usbManager: UsbManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tr.init(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(container) })
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun body(text: String, size: Float = 14f) = TextView(this).apply {
        this.text = text
        textSize = size
        setLineSpacing(0f, 1.15f)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun render() {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = tr("Settings", "Настройки")
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        // ---- Rumble
        container.addView(header(tr("Rumble", "Вибрация")))
        val on = DeviceConfig.rumbleEnabled(this)
        container.addView(body(
            tr("On: ${if (on) "yes" else "no"}    Strength: ", "Включена: ${if (on) "да" else "не"}    Сила: ") +
                "${DeviceConfig.rumbleStrength(this)}%"
        ))
        val rumbleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rumbleRow.addView(button(if (on) tr("Turn off", "Изключи") else tr("Turn on", "Включи")) {
            DeviceConfig.setRumbleEnabled(this, !DeviceConfig.rumbleEnabled(this)); render()
        })
        rumbleRow.addView(button("−10%") { DeviceConfig.setRumbleStrength(this, DeviceConfig.rumbleStrength(this) - 10); render() })
        rumbleRow.addView(button("+10%") { DeviceConfig.setRumbleStrength(this, DeviceConfig.rumbleStrength(this) + 10); render() })
        container.addView(rumbleRow)

        // ---- Shizuku help
        container.addView(header(tr("Shizuku after a restart", "Shizuku след рестарт")))
        container.addView(body(tr(
            "Without root, Shizuku stops every time the phone restarts.\n\n" +
                "Easiest: Shizuku 13.6 or newer can start by itself when the phone is on a trusted Wi‑Fi network (for example at home). " +
                "Open Shizuku and turn on its start-on-boot option, if your version has it.\n\n" +
                "Otherwise: open Shizuku → tap Start (Wireless debugging), on any Wi‑Fi.\n\n" +
                "Once it runs you can switch to mobile data. Do not turn off Developer options or USB debugging, or Shizuku stops.",
            "Без root Shizuku спира при всеки рестарт на телефона.\n\n" +
                "Най-лесно: Shizuku 13.6 или по-нов може да тръгва сам, когато телефонът е на доверена Wi‑Fi мрежа (например вкъщи). " +
                "Отвори Shizuku и включи опцията за стартиране при включване на телефона, ако я има.\n\n" +
                "Иначе: отвори Shizuku → натисни Start (Wireless debugging), на какъвто и да е Wi‑Fi.\n\n" +
                "Щом тръгне, можеш да минеш на мобилни данни. Не изключвай „Опции за програмисти“ и „USB debugging“, иначе Shizuku спира.",
        )))

        // ---- Connected USB devices
        container.addView(header(tr("Connected USB devices", "Включени USB устройства")))
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            container.addView(body(tr("None right now.", "В момента няма."), 13f))
        } else {
            for (d in devices) {
                val gip = (0 until d.interfaceCount).any {
                    val i = d.getInterface(it)
                    i.interfaceClass == 0xFF && i.interfaceSubclass == 0x47 && i.interfaceProtocol == 0xD0
                }
                container.addView(body(
                    "${d.manufacturerName ?: ""} ${d.productName ?: "?"}\n" +
                        "VID %04x  PID %04x  ".format(d.vendorId, d.productId) +
                        if (gip) tr("– Xbox One/Series protocol, supported", "– Xbox One/Series протокол, поддържа се")
                        else tr("– not an Xbox One/Series controller", "– не е Xbox One/Series контролер"),
                    13f,
                ).apply { setPadding(0, dp(6), 0, 0) })
            }
        }

        // ---- Advanced GIP quirks
        container.addView(header(tr("Advanced (troubleshooting)", "Разширени (за отстраняване на проблеми)")))
        container.addView(body(tr(
            "Change these only if a controller does not start. They apply after unplugging and plugging it in again.",
            "Промени тези само ако контролерът не тръгва. Важат след изваждане и включване.",
        ), 12f))
        fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
            container.addView(CheckBox(this).apply {
                text = label
                isChecked = value
                setOnCheckedChangeListener { _, checked -> set(checked) }
            })
        }
        toggle(tr("Send start-up packets right away (like Linux xpad)", "Изпрати стартовите команди веднага (както Linux xpad)"),
            DeviceConfig.earlyPowerOn(this)) { DeviceConfig.setEarlyPowerOn(this, it) }
        toggle(tr("Turn on the Xbox button light (LED ON)", "Включи светлината на Xbox бутона (LED ON)"),
            DeviceConfig.ledOn(this)) { DeviceConfig.setLedOn(this, it) }
        toggle(tr("Reset the audio interface (xone)", "Нулирай аудио интерфейса (xone)"),
            DeviceConfig.resetAudioIface(this)) { DeviceConfig.setResetAudioIface(this, it) }
        toggle(tr("Ask for IDENTIFY after ANNOUNCE (xone)", "Поискай IDENTIFY след ANNOUNCE (xone)"),
            DeviceConfig.sendIdentify(this)) { DeviceConfig.setSendIdentify(this, it) }
        toggle(tr("Also take over controllers the phone already supports", "Поемай и контролери, които телефонът вече поддържа"),
            DeviceConfig.forceAll(this)) { DeviceConfig.setForceAll(this, it) }

        container.addView(body("\n" + madeByLine() + "\n" + tr(
            "Based on GIP Bridge by Christopher VanZetta (MIT). Version ${BuildConfig.VERSION_NAME}.",
            "Базирано на GIP Bridge от Christopher VanZetta (MIT). Версия ${BuildConfig.VERSION_NAME}.",
        ), 12f))
    }
}
