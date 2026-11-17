package app.orchard.tv.policy

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.orchard.tv.guard.KidsWatch
import app.orchard.tv.guard.RevivalListener
import app.orchard.tv.settings.Restricted
import app.orchard.tv.system.DiagLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pauses apps that keep playing after the lock desktop comes up.
 *
 * A pause is sent only to other apps' media sessions, so the lock film keeps the sound a parent
 * enabled: it is Aerial's own ExoPlayer, without a MediaSession. Muting STREAM_MUSIC or holding
 * audio focus would silence the film too, so this gate does neither.
 *
 * Reading other apps' sessions is the public notification-listener route; the installer enables
 * [RevivalListener] for it. Without that access Android throws and this gate does nothing.
 */
internal object LockAudioGate {
    private const val TICK_MS = 1_000L

    private val started = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null

    /** Packages already logged for the current lock. */
    private val logged = HashSet<String>()
    private var denied = false

    private val tick = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun install(context: Context) {
        if (!started.compareAndSet(false, true)) return
        app = context.applicationContext
        handler.postDelayed(tick, TICK_MS)
    }

    private fun poll() {
        val context = app ?: return
        // MainActivity is the lock desktop. It is not in front while a study app opened from the
        // lock is, so that app's own sound is left alone.
        val locked = KidsWatch.homeInFront &&
            (Restricted.lock != null || PolicyModeCoordinator.isLockScreen())
        if (!locked) {
            logged.clear()
            return
        }
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val sessions = try {
            manager.getActiveSessions(ComponentName(context, RevivalListener::class.java))
        } catch (e: Exception) {
            // The installer grants RevivalListener notification access; a sideloaded install may
            // not, and getActiveSessions throws instead of returning an empty list.
            if (!denied) {
                denied = true
                DiagLog.i("lock-audio: session access failed (${e.javaClass.simpleName})")
            }
            return
        }
        denied = false
        for (controller in sessions) {
            if (controller.packageName == context.packageName) continue
            val state = controller.playbackState?.state ?: continue
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_BUFFERING) continue
            val paused = runCatching { controller.transportControls.pause() }.isSuccess
            if (paused && logged.add(controller.packageName)) {
                DiagLog.i("lock-audio: paused ${controller.packageName}")
            }
        }
    }
}
