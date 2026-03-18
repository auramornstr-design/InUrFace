/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * MainActivity.kt — Phase 4 update
 * The dashboard. Shows:
 *   - Service status (accessibility service + overlay permission)
 *   - The user's Manakit slot usage (free/plus/pro)
 *   - List of configured app overlays sourced from InteractiveFaceStore
 *   - FAB to add a new IF via app picker
 *
 * Phase 4 changes:
 *   - Removed OverlayProfileStore — replaced by InteractiveFaceStore
 *   - loadConfiguredAppItems() now reads from InteractiveFaceStore and ManakitStore
 *   - AppOverlayItem.isActive now reflects whether the IF is kept in the Manakit
 *   - RepresentativeRuntime starts TopGovernor implicitly via InYourFaceApp.onCreate()
 *     so this activity only needs to start and bind RepresentativeRuntime directly
 *   - Listens for ACTION_HEALTH_UPDATED and ACTION_AUTO_DEPLOY_ACTIVATED to
 *     refresh the dashboard list when health changes behind the scenes
 */

package com.inyourface.app.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.inyourface.app.R
import com.inyourface.app.model.AppInfo
import com.inyourface.app.model.InteractiveFaceStore
import com.inyourface.app.model.ManakitStore
import com.inyourface.app.representative.RepresentativeRuntime
import com.inyourface.app.ui.adapter.AppOverlayAdapter
import com.inyourface.app.ui.adapter.AppOverlayItem
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Views
    private lateinit var dotAccessibility: View
    private lateinit var dotOverlay: View
    private lateinit var btnEnableAccessibility: View
    private lateinit var btnEnableOverlay: View
    private lateinit var emptyState: View
    private lateinit var recyclerApps: RecyclerView
    private lateinit var fab: FloatingActionButton

    // Adapter
    private lateinit var adapter: AppOverlayAdapter

    // Service binding
    private var representativeRuntime: RepresentativeRuntime? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            representativeRuntime = (binder as RepresentativeRuntime.RepresentativeBinder).getRuntime()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            representativeRuntime = null
        }
    }

    // Refresh dashboard when health or deploy state changes
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadConfiguredApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        dotAccessibility     = findViewById(R.id.dotAccessibility)
        dotOverlay           = findViewById(R.id.dotOverlay)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay     = findViewById(R.id.btnEnableOverlay)
        emptyState           = findViewById(R.id.emptyState)
        recyclerApps         = findViewById(R.id.recyclerApps)
        fab                  = findViewById(R.id.fab)

        adapter = AppOverlayAdapter { item -> openEditor(item.appInfo.packageName, item.appInfo.label) }
        recyclerApps.layoutManager = LinearLayoutManager(this)
        recyclerApps.adapter = adapter

        fab.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, AppPickerActivity::class.java), REQUEST_PICK_APP)
        }

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnEnableOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        // Start RepresentativeRuntime — TopGovernor is already started by InYourFaceApp.onCreate()
        val serviceIntent = Intent(this, RepresentativeRuntime::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(serviceIntent)
        else
            startService(serviceIntent)

        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Listen for health and auto-deploy updates to refresh the list
        val filter = IntentFilter().apply {
            addAction(RepresentativeRuntime.ACTION_HEALTH_UPDATED)
            addAction(RepresentativeRuntime.ACTION_AUTO_DEPLOY_ACTIVATED)
        }
        registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        loadConfiguredApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        try { unbindService(serviceConnection) } catch (e: Exception) {}
        try { unregisterReceiver(refreshReceiver) } catch (e: Exception) {}
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_APP && resultCode == RESULT_OK) {
            val pkg   = data?.getStringExtra(AppPickerActivity.RESULT_PACKAGE) ?: return
            val label = data.getStringExtra(AppPickerActivity.RESULT_LABEL) ?: pkg
            openEditor(pkg, label)
        }
    }

    // ─── Status Indicators ────────────────────────────────────────────────────

    private fun updateStatusIndicators() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true

        updateDot(dotAccessibility, accessibilityEnabled)
        updateDot(dotOverlay, overlayEnabled)

        btnEnableAccessibility.visibility = if (!accessibilityEnabled) View.VISIBLE else View.GONE
        btnEnableOverlay.visibility       = if (!overlayEnabled) View.VISIBLE else View.GONE
    }

    private fun updateDot(dot: View, active: Boolean) {
        dot.background.setTint(
            ContextCompat.getColor(this, if (active) R.color.status_active else R.color.status_inactive)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, com.inyourface.app.diplomat.DiplomatRuntime::class.java)
        val setting = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (e: Exception) { "" }
        return setting.contains(expected.flattenToString())
    }

    // ─── Configured Apps ──────────────────────────────────────────────────────

    private fun loadConfiguredApps() {
        mainScope.launch {
            val items = withContext(Dispatchers.IO) { loadConfiguredAppItems() }
            adapter.submitList(items)
            emptyState.visibility  = if (items.isEmpty()) View.VISIBLE else View.GONE
            recyclerApps.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * Reads all Interactive Faces from InteractiveFaceStore, grouped by package.
     * isActive reflects whether the IF is currently kept in the Manakit.
     * elementCount reflects how many element overrides the most-recent IF for that package has.
     */
    private fun loadConfiguredAppItems(): List<AppOverlayItem> {
        val keysDir = java.io.File(filesDir, "translation_keys")
        if (!keysDir.exists()) return emptyList()

        val manakit = ManakitStore.load(this)
        val keptIds = manakit?.keptFaceIds ?: emptyList()

        return keysDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                val pkg = file.nameWithoutExtension
                val appInfo = try {
                    val pm = packageManager
                    val appLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    val versionName = try { pm.getPackageInfo(pkg, 0).versionName ?: "" } catch (e: Exception) { "" }
                    AppInfo(packageName = pkg, label = appLabel, versionName = versionName, isSystemApp = false)
                } catch (e: Exception) { return@mapNotNull null }

                // Get the most recently modified IF for this package
                val faces        = InteractiveFaceStore.loadAll(this, pkg)
                val primaryFace  = faces.maxByOrNull { it.lastModifiedAtMs }
                val isKept       = primaryFace?.id?.let { it in keptIds } ?: false
                val elementCount = primaryFace?.elementOverrides?.size ?: 0

                AppOverlayItem(appInfo = appInfo, elementCount = elementCount, isActive = isKept)
            } ?: emptyList()
    }

    private fun openEditor(packageName: String, appLabel: String) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_PACKAGE, packageName)
            putExtra(EditorActivity.EXTRA_LABEL, appLabel)
        })
    }

    companion object {
        private const val REQUEST_PICK_APP = 1001
    }
}
