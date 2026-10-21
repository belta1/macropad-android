package dev.belta.macropad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.belta.macropad.model.AppSettings

/** Starts the macropad after the tablet boots (if enabled in settings). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettings(context).openOnBoot) return
        MacropadService.start(context)
        MainActivity.bringToFront(context)
    }
}
