package com.vanzetta.gipbridge

import android.content.Context
import java.util.Locale

/** Name shown as the author ("Made by … with Claude"). Leave empty to show only Claude. */
const val AUTHOR_NAME = "GeorgeTheFirst"

/**
 * Tiny two-language helper (English / Bulgarian). The UI is built in code, so every text is
 * written in place as tr("English", "Български"). The language follows the phone unless the
 * user picks one with the EN / BG button.
 */
object Tr {
    @Volatile var bulgarian: Boolean = false
        private set

    fun init(ctx: Context) {
        bulgarian = when (DeviceConfig.language(ctx)) {
            "bg" -> true
            "en" -> false
            else -> Locale.getDefault().language == "bg"
        }
    }

    fun set(ctx: Context, bg: Boolean) {
        DeviceConfig.setLanguage(ctx, if (bg) "bg" else "en")
        bulgarian = bg
    }
}

fun tr(en: String, bg: String): String = if (Tr.bulgarian) bg else en

fun madeByLine(): String =
    if (AUTHOR_NAME.isBlank()) tr("Made with Claude (Anthropic).", "Направено с Claude (Anthropic).")
    else tr("Made by $AUTHOR_NAME with Claude (Anthropic).", "Направено от $AUTHOR_NAME с Claude (Anthropic).")
