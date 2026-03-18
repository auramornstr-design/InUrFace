/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * InYourFaceApp.kt — Phase 4 update
 * Application class. Holds shared singletons accessible to all services.
 *
 * Phase 3: MarkerSurface — the one data structure both runtimes share.
 * Phase 4: Adds TopGovernor service startup on application init.
 *          Adds ManakitStore as an accessible reference point.
 *
 * Neither DiplomatRuntime nor RepresentativeRuntime call each other directly.
 * The MarkerSurface remains the bridge between them.
 * The TopGovernor runs independently and communicates via broadcasts + binder.
 */

package com.inyourface.app

import android.app.Application
import android.content.Intent
import com.inyourface.app.governor.TopGovernor
import com.inyourface.app.model.ManakitStore
import com.inyourface.app.overlay.MarkerSurface

class InYourFaceApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Ensure Manakit exists for this device user on first launch
        ManakitStore.loadOrCreate(this)

        // Start the TopGovernor supervisor service
        val governorIntent = Intent(this, TopGovernor::class.java)
        startService(governorIntent)

        android.util.Log.d("InYourFace", "Application initialized. TopGovernor started.")
    }

    companion object {
        /**
         * The shared MarkerSurface — the only data structure both runtimes access.
         * Representative writes request markers. Diplomat writes response markers.
         * Neither runtime directly calls the other.
         */
        val markerSurface = MarkerSurface()
    }
}
