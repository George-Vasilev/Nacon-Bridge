package com.vanzetta.gipbridge

import android.content.Context

private const val PREFS_NAME = "gip_bridge_config"
private const val KEY_RUMBLE_ENABLED = "rumble_enabled"
private const val KEY_RUMBLE_STRENGTH = "rumble_strength"
private const val KEY_LANGUAGE = "language"
private const val KEY_SETUP_COLLAPSED = "setup_collapsed"
private const val KEY_FORCE_ALL = "force_all_gip"

// GIP handshake quirks, configurable so a controller that behaves differently can be tried
// without a rebuild (Settings -> Advanced).
private const val KEY_EARLY_POWER_ON = "quirk_early_power_on"
private const val KEY_LED_ON = "quirk_led_on"
private const val KEY_SEND_IDENTIFY = "quirk_send_identify"
private const val KEY_RESET_AUDIO_IFACE = "quirk_reset_audio_iface"

object DeviceConfig {
    fun rumbleEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_RUMBLE_ENABLED, true)
    fun setRumbleEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RUMBLE_ENABLED, enabled).apply()
    }

    /** 0-100, applied as a multiplier on top of whatever strength the game requested. */
    fun rumbleStrength(ctx: Context) = prefs(ctx).getInt(KEY_RUMBLE_STRENGTH, 100)
    fun setRumbleStrength(ctx: Context, percent: Int) {
        prefs(ctx).edit().putInt(KEY_RUMBLE_STRENGTH, percent.coerceIn(0, 100)).apply()
    }

    /** "en", "bg" or null (follow the phone's language). */
    fun language(ctx: Context): String? = prefs(ctx).getString(KEY_LANGUAGE, null)
    fun setLanguage(ctx: Context, lang: String) = prefs(ctx).edit().putString(KEY_LANGUAGE, lang).apply()

    /** The "Before you start" card is folded once the controller has worked once. */
    fun setupCollapsed(ctx: Context) = prefs(ctx).getBoolean(KEY_SETUP_COLLAPSED, false)
    fun setSetupCollapsed(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_SETUP_COLLAPSED, v).apply()

    /** Also take over GIP controllers the phone already supports by itself (off by default). */
    fun forceAll(ctx: Context) = prefs(ctx).getBoolean(KEY_FORCE_ALL, false)
    fun setForceAll(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_FORCE_ALL, v).apply()

    /** Send the start-up packets (POWER=ON and Linux xpad's per-brand init packets) right
     *  after the interface is claimed, without waiting for ANNOUNCE. */
    fun earlyPowerOn(ctx: Context) = prefs(ctx).getBoolean(KEY_EARLY_POWER_ON, true)
    fun setEarlyPowerOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_EARLY_POWER_ON, v).apply()

    /** Send a GIP LED=ON command after POWER=ON (Xbox button light). */
    fun ledOn(ctx: Context) = prefs(ctx).getBoolean(KEY_LED_ON, true)
    fun setLedOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_LED_ON, v).apply()

    /** Ask for the GIP IDENTIFY descriptor after ANNOUNCE (xone does, xpad does not; the
     *  reply is a chunked packet this app does not reassemble). Off by default. */
    fun sendIdentify(ctx: Context) = prefs(ctx).getBoolean(KEY_SEND_IDENTIFY, false)
    fun setSendIdentify(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_SEND_IDENTIFY, v).apply()

    /** Re-assert interface 1 alt-setting 0 (xone: "mandatory for certain third party devices"). */
    fun resetAudioIface(ctx: Context) = prefs(ctx).getBoolean(KEY_RESET_AUDIO_IFACE, true)
    fun setResetAudioIface(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_RESET_AUDIO_IFACE, v).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
