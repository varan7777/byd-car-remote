package ua.zaika.carremote

import android.app.Activity
import android.os.Bundle

/**
 * Invisible entry point for the shell watchdog. A force-stopped app can only be revived from
 * shell by starting an activity (its services don't resolve while stopped), but a visible
 * activity would steal the screen on wake. This one is transparent, starts the remote service
 * and finishes immediately, so the revive is effectively silent.
 */
class BootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarRemoteService.start(this)
        finish()
    }
}
