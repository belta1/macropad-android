package dev.belta.macropad.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the project's GitHub Releases: finds the latest release's APK, downloads it and
 * hands it to Android's package installer (which asks before installing). Network calls block,
 * so run them off the main thread. Failures throw [IOException] with a message fit for a dialog.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/belta1/macropad-android/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    /** The latest release with an APK, or null when there is none yet. */
    fun latest(): Release? {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = try { connection.responseCode } catch (e: IOException) { throw IOException("GitHub can't be reached") }
            if (code == 404) return null
            if (code !in 200..299) throw IOException("GitHub answered $code")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            return Release(json.optString("tag_name").removePrefix("v"), json.optString("body"), apk.getString("browser_download_url"))
        } finally {
            connection.disconnect()
        }
    }

    /** True when [candidate] ("1.7.0") is a later version than [current] ("1.6.0"). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun download(context: Context, release: Release): File {
        val file = File(context.cacheDir, "update.apk")
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) throw IOException("Download failed (${connection.responseCode})")
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        if (!sameSigner(context, file)) {
            file.delete()
            throw IOException("This release is signed with a different key than the installed app, so Android won't " +
                "install it over this one. Install it by hand (that removes the app's data) or ask for a release " +
                "built with the usual key.")
        }
        return file
    }

    /** Android only replaces an app with an APK signed by the same key. */
    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != context.packageName) return false
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return false
        val ours = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        return theirs == ours
    }

    /** Starts the install; Android shows its confirmation through [StatusReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("macropad.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val status = PendingIntent.getBroadcast(context, id, Intent(context, StatusReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }

    /** The installer's answers: show its confirmation screen, or say why it failed. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                    Toast.makeText(context, "Update not installed: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
