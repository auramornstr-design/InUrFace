/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * EditorActivity.kt — Phase 4 update
 * The overlay editor control room for a specific foreign app.
 *
 * This is where the user:
 *   - Activates Teach Mode to learn new elements
 *   - Activates Customize Mode to rearrange and style the overlay
 *   - Accepts or cancels changes
 *   - Views and manages Interactive Faces for this app
 *   - Keeps or releases an IF in their Manakit
 *   - Sees health status, current persona, and audit suggestions
 *
 * Phase 4 changes:
 *   - OverlayProfile / OverlayProfileStore / ProfileSummary removed
 *   - InteractiveFace / InteractiveFaceStore / IFSummary used throughout
 *   - IFAdapter replaces ProfileAdapter
 *   - activateTeachMode() uses Phase 4 sourceLabel signature — no targetCell
 *   - New broadcast actions handled: AUDIT_SUGGESTED, HEALTH_UPDATED, PERSONA_CHANGED
 *   - confirmDeleteOverlay() now deletes IFs and releases from Manakit
 *   - Keep/release IF in Manakit wired to runtime
 *
 * All actual overlay work is still delegated to RepresentativeRuntime.
 * No Diplomat contact here.
 */

package com.inyourface.app.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.inyourface.app.R
import com.inyourface.app.model.*
import com.inyourface.app.representative.RepresentativeRuntime
import com.inyourface.app.ui.adapter.ElementAdapter
import com.inyourface.app.ui.adapter.IFAdapter
import kotlinx.coroutines.*

class EditorActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var foreignPackage: String
    private lateinit var appLabel: String

    // Views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bannerTeachMode: LinearLayout
    private lateinit var bannerTeachText: TextView
    private lateinit var btnCancelTeach: TextView
    private lateinit var bannerKeyStale: LinearLayout
    private lateinit var btnRefreshKey: TextView
    private lateinit var btnCustomizeMode: MaterialButton
    private lateinit var btnTeachMode: MaterialButton
    private lateinit var teachHint: TextView
    private lateinit var barCustomizeActions: LinearLayout
    private lateinit var btnAcceptCustomize: MaterialButton
    private lateinit var btnCancelCustomize: MaterialButton
    private lateinit var emptyElements: View
    private lateinit var recyclerElements: RecyclerView
    private lateinit var recyclerIFs: RecyclerView
    private lateinit var btnNewIF: TextView

    // Adapters
    private lateinit var elementAdapter: ElementAdapter
    private lateinit var ifAdapter: IFAdapter

    // Service
    private var runtime: RepresentativeRuntime? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = (binder as RepresentativeRuntime.RepresentativeBinder).getRuntime()
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null }
    }

    // State
    private var isCustomizeModeActive = false
    private var isTeachModeActive     = false
    private var activeTranslationKey: TranslationKey? = null

    // ─── Broadcast Receiver ───────────────────────────────────────────────────

    private val editorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                RepresentativeRuntime.ACTION_GRID_READY -> showCustomizeModeUI()

                RepresentativeRuntime.ACTION_TEACH_COMPLETE -> {
                    val elementId = intent.getStringExtra(RepresentativeRuntime.EXTRA_ELEMENT_ID)
                    onTeachComplete(elementId)
                }
                RepresentativeRuntime.ACTION_TEACH_FAILED -> {
                    val reason = intent.getStringExtra(RepresentativeRuntime.EXTRA_TEACH_FAIL_REASON)
                    cancelTeachMode()
                    Toast.makeText(context, "Teach failed: $reason", Toast.LENGTH_SHORT).show()
                }
                RepresentativeRuntime.ACTION_CAPABILITY_REJECTED -> {
                    val reason = intent.getStringExtra(RepresentativeRuntime.EXTRA_REJECTION_REASON)
                    showRejectionToast(reason)
                }
                RepresentativeRuntime.ACTION_KEY_STALE ->
                    bannerKeyStale.visibility = View.VISIBLE

                // Phase 4 — IF health updated for this package
                RepresentativeRuntime.ACTION_HEALTH_UPDATED -> {
                    val pkg = intent.getStringExtra(RepresentativeRuntime.EXTRA_PACKAGE)
                    if (pkg == foreignPackage) loadIFsAndRefresh()
                }
                // Phase 4 — Active persona changed by battery degradation
                RepresentativeRuntime.ACTION_PERSONA_CHANGED -> {
                    val pkg = intent.getStringExtra(RepresentativeRuntime.EXTRA_PACKAGE)
                    if (pkg == foreignPackage) {
                        val type = intent.getStringExtra(RepresentativeRuntime.EXTRA_PERSONA_TYPE) ?: ""
                        Toast.makeText(context, "Persona switched to $type.", Toast.LENGTH_SHORT).show()
                        loadIFsAndRefresh()
                    }
                }
                // Phase 4 — TopGovernor suggests an audit
                RepresentativeRuntime.ACTION_AUDIT_SUGGESTED -> {
                    val faceId    = intent.getStringExtra(RepresentativeRuntime.EXTRA_FACE_ID) ?: return
                    val pkg       = intent.getStringExtra(RepresentativeRuntime.EXTRA_PACKAGE) ?: return
                    val auditType = intent.getStringExtra(RepresentativeRuntime.EXTRA_AUDIT_TYPE) ?: return
                    if (pkg == foreignPackage) promptAuditConfirmation(faceId, auditType)
                }
                // Phase 4 — Audit finished
                RepresentativeRuntime.ACTION_AUDIT_COMPLETE -> {
                    val pkg = intent.getStringExtra(RepresentativeRuntime.EXTRA_PACKAGE)
                    if (pkg == foreignPackage) {
                        Toast.makeText(context, "Audit complete — health restored.", Toast.LENGTH_SHORT).show()
                        loadIFsAndRefresh()
                    }
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        foreignPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        appLabel       = intent.getStringExtra(EXTRA_LABEL) ?: foreignPackage

        bindViews()
        setupToolbar()
        setupAdapters()
        setupButtonListeners()
        bindRepresentativeService()
        registerEditorReceiver()
        loadTranslationKeyAndRefreshUI()
    }

    override fun onResume() {
        super.onResume()
        loadTranslationKeyAndRefreshUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { unregisterReceiver(editorReceiver) } catch (e: Exception) {}
        try { unbindService(serviceConnection) } catch (e: Exception) {}
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home        -> { onBackPressedDispatcher.onBackPressed(); true }
        R.id.action_delete_overlay -> { confirmDeleteOverlay(); true }
        R.id.action_pre_scout    -> { showPreScoutPicker(); true }
        else                     -> super.onOptionsItemSelected(item)
    }

    // ─── View Binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        toolbar              = findViewById(R.id.toolbar)
        bannerTeachMode      = findViewById(R.id.bannerTeachMode)
        bannerTeachText      = findViewById(R.id.bannerTeachText)
        btnCancelTeach       = findViewById(R.id.btnCancelTeach)
        bannerKeyStale       = findViewById(R.id.bannerKeyStale)
        btnRefreshKey        = findViewById(R.id.btnRefreshKey)
        btnCustomizeMode     = findViewById(R.id.btnCustomizeMode)
        btnTeachMode         = findViewById(R.id.btnTeachMode)
        teachHint            = findViewById(R.id.teachHint)
        barCustomizeActions  = findViewById(R.id.barCustomizeActions)
        btnAcceptCustomize   = findViewById(R.id.btnAcceptCustomize)
        btnCancelCustomize   = findViewById(R.id.btnCancelCustomize)
        emptyElements        = findViewById(R.id.emptyElements)
        recyclerElements     = findViewById(R.id.recyclerElements)
        // Phase 4: recyclerProfiles → recyclerIFs (same view id, renamed in meaning only)
        recyclerIFs          = findViewById(R.id.recyclerProfiles)
        btnNewIF             = findViewById(R.id.btnNewProfile)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = appLabel
            setDisplayHomeAsUpEnabled(true)
        }
    }

    // ─── Adapters ─────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        elementAdapter = ElementAdapter(
            onDelete = { element -> confirmDeleteElement(element) },
            onEditCapability = { element, capability -> showCapabilityEditor(element, capability) }
        )
        recyclerElements.layoutManager = LinearLayoutManager(this)
        recyclerElements.adapter = elementAdapter

        ifAdapter = IFAdapter(
            onKeep   = { summary -> keepIF(summary) },
            onDelete = { summary -> confirmDeleteIF(summary) }
        )
        recyclerIFs.layoutManager = LinearLayoutManager(this)
        recyclerIFs.adapter = ifAdapter
    }

    // ─── Button Listeners ─────────────────────────────────────────────────────

    private fun setupButtonListeners() {
        btnCustomizeMode.setOnClickListener  { handleCustomizeModeToggle() }
        btnTeachMode.setOnClickListener      { handleTeachModeToggle() }
        btnCancelTeach.setOnClickListener    { cancelTeachMode() }
        btnAcceptCustomize.setOnClickListener { acceptCustomizeMode() }
        btnCancelCustomize.setOnClickListener { cancelCustomizeMode() }
        btnRefreshKey.setOnClickListener     { refreshTranslationKey() }
        btnNewIF.setOnClickListener          { showNewIFDialog() }
    }

    // ─── Customize Mode ───────────────────────────────────────────────────────

    private fun handleCustomizeModeToggle() {
        if (isCustomizeModeActive) { cancelCustomizeMode(); return }
        val rt = runtime ?: run {
            Toast.makeText(this, "Overlay service not running.", Toast.LENGTH_SHORT).show(); return
        }
        btnCustomizeMode.isEnabled = false
        btnTeachMode.isEnabled     = false
        rt.activateCustomizeMode { _ -> /* grid ready comes via broadcast */ }
    }

    private fun showCustomizeModeUI() {
        isCustomizeModeActive      = true
        btnCustomizeMode.isEnabled = true
        btnTeachMode.isEnabled     = true
        btnCustomizeMode.text      = getString(R.string.customize_mode)
        barCustomizeActions.visibility = View.VISIBLE
    }

    private fun acceptCustomizeMode() {
        runtime?.commitCustomizeMode()
        isCustomizeModeActive = false
        barCustomizeActions.visibility = View.GONE
        loadTranslationKeyAndRefreshUI()
    }

    private fun cancelCustomizeMode() {
        runtime?.cancelCustomizeMode()
        isCustomizeModeActive = false
        barCustomizeActions.visibility = View.GONE
    }

    // ─── Teach Mode (Phase 4 — sourceLabel signature) ─────────────────────────

    private fun handleTeachModeToggle() {
        if (isTeachModeActive) { cancelTeachMode(); return }
        isTeachModeActive = true
        bannerTeachMode.visibility = View.VISIBLE
        bannerTeachText.text       = getString(R.string.teach_mode_active)
        teachHint.text             = getString(R.string.teach_hint, appLabel)
        teachHint.visibility       = View.VISIBLE

        val rt = runtime ?: run { cancelTeachMode(); return }

        // Phase 4 signature: sourceLabel, onComplete, onFailed — no targetCell
        rt.activateTeachMode(
            sourceLabel = appLabel,
            onComplete  = { result -> onTeachComplete(result.learnedElementId) },
            onFailed    = { reason ->
                cancelTeachMode()
                Toast.makeText(this, "Teach failed: $reason", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun cancelTeachMode() {
        isTeachModeActive = false
        runtime?.cancelTeachMode()
        bannerTeachMode.visibility = View.GONE
        teachHint.visibility       = View.GONE
    }

    private fun onTeachComplete(elementId: String?) {
        isTeachModeActive = false
        bannerTeachMode.visibility = View.GONE
        teachHint.visibility       = View.GONE
        if (elementId != null) Toast.makeText(this, "Element learned successfully.", Toast.LENGTH_SHORT).show()
        loadTranslationKeyAndRefreshUI()
    }

    // ─── Capability Editor ────────────────────────────────────────────────────

    private fun showCapabilityEditor(element: MappedElement, capability: CapabilityType) {
        val rt = runtime ?: return
        when (capability) {
            CapabilityType.LABEL -> {
                val input = EditText(this).apply {
                    hint = "Enter label"
                    setText((element.capabilityStates[capability.id]?.currentValue as? CapabilityValue.Label)?.text ?: "")
                }
                AlertDialog.Builder(this)
                    .setTitle("Rename Element").setView(input)
                    .setPositiveButton("Apply") { _, _ ->
                        val newLabel = input.text.toString().trim()
                        if (newLabel.isNotEmpty()) {
                            rt.requestCapabilityChange(element.id, capability, CapabilityValue.Label(newLabel)) { accepted, reason ->
                                if (accepted) loadTranslationKeyAndRefreshUI() else showRejectionToast(reason?.name)
                            }
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
            CapabilityType.RECOLOR -> {
                val colors = listOf(
                    "#E94560" to "Red", "#2563A8" to "Blue", "#1A7A1A" to "Green",
                    "#7A1A7A" to "Purple", "#7A5C1E" to "Amber", "#1A7A7A" to "Teal",
                    "#0F3460" to "Navy", "#555555" to "Gray"
                )
                AlertDialog.Builder(this).setTitle("Choose Color")
                    .setItems(colors.map { it.second }.toTypedArray()) { _, index ->
                        rt.requestCapabilityChange(element.id, capability, CapabilityValue.Recolor(colors[index].first)) { accepted, reason ->
                            if (accepted) loadTranslationKeyAndRefreshUI() else showRejectionToast(reason?.name)
                        }
                    }.show()
            }
            CapabilityType.OPACITY -> {
                val options = arrayOf("100%", "80%", "60%", "40%", "20%")
                val values  = listOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f)
                AlertDialog.Builder(this).setTitle("Opacity")
                    .setItems(options) { _, index ->
                        rt.requestCapabilityChange(element.id, capability, CapabilityValue.Opacity(values[index])) { accepted, reason ->
                            if (accepted) loadTranslationKeyAndRefreshUI() else showRejectionToast(reason?.name)
                        }
                    }.show()
            }
            CapabilityType.ACTION_TYPE -> {
                val gestures = GestureType.values()
                AlertDialog.Builder(this).setTitle("Action Type")
                    .setItems(gestures.map { it.name.replace('_', ' ') }.toTypedArray()) { _, index ->
                        rt.requestCapabilityChange(element.id, capability, CapabilityValue.ActionType(gestures[index])) { accepted, reason ->
                            if (accepted) loadTranslationKeyAndRefreshUI() else showRejectionToast(reason?.name)
                        }
                    }.show()
            }
            else -> Toast.makeText(this, "Use Customize Mode to adjust ${capability.label}.", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── IF Management ────────────────────────────────────────────────────────

    private fun showNewIFDialog() {
        val input = EditText(this).apply { hint = getString(R.string.if_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_if))
            .setView(input)
            .setPositiveButton(getString(R.string.save_if)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        val keyHash = try {
                            val info = packageManager.getPackageInfo(foreignPackage, 0)
                            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                                info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                            "${foreignPackage}_${code}_${info.versionName}".hashCode().toString()
                        } catch (e: Exception) { foreignPackage }
                        val face = InteractiveFace.create(name, foreignPackage, keyHash)
                        InteractiveFaceStore.save(this@EditorActivity, face)
                        withContext(Dispatchers.Main) { loadIFsAndRefresh() }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Keep an IF in the Manakit — auto-deploy will activate for this app from now on.
     * Shows a toast if the Manakit is full (Free tier limit reached).
     */
    private fun keepIF(summary: IFSummary) {
        val rt = runtime ?: return
        val kept = rt.keepFaceInManakit(summary.id)
        if (kept) {
            Toast.makeText(this, "'${summary.name}' added to your Manakit.", Toast.LENGTH_SHORT).show()
            loadIFsAndRefresh()
        } else {
            val manakit = rt.getManakit()
            Toast.makeText(
                this,
                "Manakit full (${manakit.usedSlots}/${manakit.slotLimit} slots). Upgrade to Plus (8 IFs) or Pro (50 IFs).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmDeleteIF(summary: IFSummary) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage("Delete IF \"${summary.name}\"?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    // Release from Manakit first so it no longer auto-deploys
                    runtime?.releaseFaceFromManakit(summary.id)
                    InteractiveFaceStore.delete(this@EditorActivity, summary.id, foreignPackage)
                    withContext(Dispatchers.Main) { loadIFsAndRefresh() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── Audit Prompt (Phase 4) ───────────────────────────────────────────────

    private fun promptAuditConfirmation(faceId: String, auditTypeName: String) {
        val auditType = try { AuditType.valueOf(auditTypeName) } catch (e: Exception) { return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audit_suggested_title))
            .setMessage(getString(R.string.audit_suggested_message, auditType.label))
            .setPositiveButton(getString(R.string.run_audit)) { _, _ ->
                runtime?.requestAudit(faceId, foreignPackage, auditType) {
                    // AuditComplete broadcast will handle UI refresh
                }
                Toast.makeText(this, getString(R.string.audit_running), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── Data Loading ─────────────────────────────────────────────────────────

    private fun loadTranslationKeyAndRefreshUI() {
        scope.launch {
            val key = withContext(Dispatchers.IO) {
                com.inyourface.app.diplomat.TranslationKeyStore.load(this@EditorActivity, foreignPackage)
            }
            activeTranslationKey = key
            val elements = key?.mappedElements?.values?.toList() ?: emptyList()
            elementAdapter.submitList(elements)
            emptyElements.visibility  = if (elements.isEmpty()) View.VISIBLE else View.GONE
            recyclerElements.visibility = if (elements.isNotEmpty()) View.VISIBLE else View.GONE
            loadIFsAndRefresh()
        }
    }

    private fun loadIFsAndRefresh() {
        scope.launch {
            val summaries = withContext(Dispatchers.IO) {
                InteractiveFaceStore.summaries(this@EditorActivity, foreignPackage)
            }
            ifAdapter.submitList(summaries)
        }
    }

    // ─── Delete Overlay ───────────────────────────────────────────────────────

    private fun confirmDeleteOverlay() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete))
            .setMessage(getString(R.string.confirm_delete_message, appLabel))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    // Delete TranslationKey
                    com.inyourface.app.diplomat.TranslationKeyStore.delete(this@EditorActivity, foreignPackage)
                    // Release and delete all IFs for this package
                    val faces = InteractiveFaceStore.loadAll(this@EditorActivity, foreignPackage)
                    faces.forEach { face ->
                        runtime?.releaseFaceFromManakit(face.id)
                        InteractiveFaceStore.delete(this@EditorActivity, face.id, foreignPackage)
                    }
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── Pre-Scout ────────────────────────────────────────────────────────────

    private fun showPreScoutPicker() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, AppPickerActivity::class.java), REQUEST_PRE_SCOUT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PRE_SCOUT && resultCode == android.app.Activity.RESULT_OK) {
            val pkg = data?.getStringExtra(AppPickerActivity.RESULT_PACKAGE) ?: return
            runtime?.requestPreScout(pkg) {
                Toast.makeText(this, "Pre-scan complete for $pkg.", Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(this, getString(R.string.pre_scout_queued), Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Key Refresh ──────────────────────────────────────────────────────────

    private fun refreshTranslationKey() {
        bannerKeyStale.visibility = View.GONE
        scope.launch(Dispatchers.IO) {
            com.inyourface.app.diplomat.TranslationKeyStore.delete(this@EditorActivity, foreignPackage)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@EditorActivity, "Layout refreshed. Re-teach any shifted elements.", Toast.LENGTH_LONG).show()
                loadTranslationKeyAndRefreshUI()
            }
        }
    }

    // ─── Element Delete ───────────────────────────────────────────────────────

    private fun confirmDeleteElement(element: MappedElement) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage("Remove this element from the overlay?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    val key = com.inyourface.app.diplomat.TranslationKeyStore.load(this@EditorActivity, foreignPackage) ?: return@launch
                    com.inyourface.app.diplomat.TranslationKeyStore.save(this@EditorActivity, key.copy(mappedElements = key.mappedElements - element.id))
                    withContext(Dispatchers.Main) { loadTranslationKeyAndRefreshUI() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── Broadcast Registration ───────────────────────────────────────────────

    private fun registerEditorReceiver() {
        val filter = IntentFilter().apply {
            addAction(RepresentativeRuntime.ACTION_GRID_READY)
            addAction(RepresentativeRuntime.ACTION_TEACH_COMPLETE)
            addAction(RepresentativeRuntime.ACTION_TEACH_FAILED)
            addAction(RepresentativeRuntime.ACTION_CAPABILITY_REJECTED)
            addAction(RepresentativeRuntime.ACTION_KEY_STALE)
            addAction(RepresentativeRuntime.ACTION_HEALTH_UPDATED)      // Phase 4
            addAction(RepresentativeRuntime.ACTION_PERSONA_CHANGED)     // Phase 4
            addAction(RepresentativeRuntime.ACTION_AUDIT_SUGGESTED)     // Phase 4
            addAction(RepresentativeRuntime.ACTION_AUDIT_COMPLETE)      // Phase 4
        }
        registerReceiver(editorReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    private fun bindRepresentativeService() {
        bindService(Intent(this, RepresentativeRuntime::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private fun showRejectionToast(reason: String?) {
        val msg = when (reason) {
            RejectionReason.ZONE_FORBIDDEN.name           -> getString(R.string.rejection_zone_forbidden)
            RejectionReason.SECURITY_RESTRICTED.name      -> getString(R.string.rejection_security)
            RejectionReason.GESTURE_BLOCKED.name          -> getString(R.string.rejection_gesture_blocked)
            RejectionReason.CAPABILITY_NOT_AVAILABLE.name -> getString(R.string.rejection_not_available)
            RejectionReason.APP_STATE_INCOMPATIBLE.name   -> getString(R.string.rejection_app_state)
            RejectionReason.TRANSLATION_KEY_STALE.name    -> getString(R.string.rejection_stale_key)
            else -> "Change was blocked."
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PACKAGE   = "extra_package"
        const val EXTRA_LABEL     = "extra_label"
        private const val REQUEST_PRE_SCOUT = 2001
    }
}
