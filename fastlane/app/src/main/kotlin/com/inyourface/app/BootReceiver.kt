/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * BootReceiver.kt — Phase 4 update
 * Restarts both foreground services after device reboot.
 *
 * RepresentativeRuntime — UI bridge and Manakit/queue API layer.
 * TopGovernor           — Standalone IF supervisor. Must restart with the
 *                         Representative so health monitoring and auto-deploy
 *                         resume immediately after boot.
 *
 * DiplomatRuntime (AccessibilityService) is managed by Android directly —
 * the OS restores it automatically if the user has granted accessibility
 * permission. No manual restart needed for the Diplomat.
 */

package com.inyourface.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.inyourface.app.governor.TopGovernor
import com.inyourface.app.representative.RepresentativeRuntime

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        listOf(
            Intent(context, RepresentativeRuntime::class.java),
            Intent(context, TopGovernor::class.java)
        ).forEach { serviceIntent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        android.util.Log.d("BootReceiver", "RepresentativeRuntime and TopGovernor restarted after boot.")
    }
}
