package dev.belta.macropad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.belta.macropad.model.AppSettings

/**
 * Starts the macropad after the tablet boots (if enabled in settings), and again after an update
 * replaced the running app (⚙ → Updates), so the computer gets its keyboard back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> if (!AppSettings(context).openOnBoot) return
            Intent.ACTION_MY_PACKAGE_REPLACED -> {}
            else -> return
        }
        MacropadService.start(context)
        MainActivity.bringToFront(context)
    }
}
