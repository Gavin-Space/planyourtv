package app.orchard.tv.parent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Installs a verified parent APK through the system PackageInstaller.
 *
 * The session is deliberately not silent: Android shows its own confirmation,
 * so installing an update always needs the person holding the phone to agree.
 */
internal class ParentInstaller(private val context: Context) {

    enum class Start { STARTED, NEED_PERMISSION, FAILED }

    var onResult: ((status: Int, message: String?) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) ?: return
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmation = intent.getParcelableExtraCompat()
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { this@ParentInstaller.context.startActivity(confirmation) }
                }
                return
            }
            onResult?.invoke(status, message)
        }
    }

    fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun register() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun start(apk: File): Start {
        if (!canInstall()) return Start.NEED_PERMISSION
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(APK_ENTRY, 0L, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName),
                    flags,
                )
                session.commit(pending.intentSender)
            }
            Start.STARTED
        } catch (e: Exception) {
            Start.FAILED
        }
    }

    private fun Intent.getParcelableExtraCompat(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        const val ACTION_INSTALL_RESULT = "app.orchard.tv.parent.INSTALL_RESULT"
        const val APK_ENTRY = "base.apk"
    }
}
