/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * SettingsActivity.kt — Phase 4 update
 * App-level settings and about screen.
 * All settings are stored in SharedPreferences.
 * No foreign app interaction occurs here — pure Representative territory.
 *
 * Phase 4 change:
 *   - clearAllData() now clears interactive_faces and manakit directories
 *     instead of the removed overlay_profiles directory.
 *   - translation_keys is still cleared (TranslationKey store is unchanged).
 */

package com.inyourface.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar
import com.inyourface.app.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.brand_deep, theme))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.settings_title)
            setTitleTextColor(resources.getColor(R.color.brand_on_surface, theme))
            setBackgroundColor(resources.getColor(R.color.brand_deep, theme))
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)

        root.addView(buildToggleRow(getString(R.string.pref_haptic),          PREF_HAPTIC,         true))
        root.addView(buildToggleRow(getString(R.string.pref_show_grid_coords), PREF_GRID_COORDS,   false))
        root.addView(buildToggleRow(getString(R.string.pref_auto_prescout),   PREF_AUTO_PRESCOUT,   true))

        root.addView(buildLinkRow("Accessibility Settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(buildLinkRow("Display Over Other Apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        // Phase 4: label reflects IF/Manakit data instead of "profiles"
        root.addView(buildLinkRow(getString(R.string.clear_all_data)) { confirmClearAll() })

        root.addView(buildAboutRow())

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ─── Row Builders ─────────────────────────────────────────────────────────

    private fun buildToggleRow(label: String, key: String, default: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            val dp56 = (56 * resources.displayMetrics.density).toInt()
            setPadding(dp16, 0, dp16, 0)
            minimumHeight = dp56

            val labelView = TextView(context).apply {
                text = label
                setTextColor(resources.getColor(R.color.brand_on_surface, theme))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }
            val toggle = SwitchCompat(context).apply {
                isChecked = prefs.getBoolean(key, default)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            }
            addView(labelView)
            addView(toggle)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
    }

    private fun buildLinkRow(label: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            val dp56 = (56 * resources.displayMetrics.density).toInt()
            setPadding(dp16, 0, dp16, 0)
            minimumHeight = dp56
            isClickable = true
            isFocusable  = true
            background = android.util.TypedValue().also {
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId.let { getDrawable(it) }
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                text = label
                setTextColor(resources.getColor(R.color.brand_on_surface, theme))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            })
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
    }

    private fun buildAboutRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            val dp24 = (24 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp24, dp16, dp24)

            val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "1.0.0" }

            addView(TextView(context).apply {
                text = getString(R.string.version_label, version)
                setTextColor(resources.getColor(R.color.brand_on_surface_secondary, theme))
                textSize = 13f
            })
            addView(TextView(context).apply {
                text = getString(R.string.authors)
                setTextColor(resources.getColor(R.color.brand_on_surface_secondary, theme))
                textSize = 13f
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
    }

    // ─── Clear All Data ───────────────────────────────────────────────────────

    private fun confirmClearAll() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_all_data))
            .setMessage(getString(R.string.clear_all_data_message))
            .setPositiveButton(getString(R.string.clear_all_data)) { _, _ -> clearAllData() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Phase 4: clears translation_keys, interactive_faces, and manakit.
     * overlay_profiles directory no longer exists — removed with OverlayProfile.
     */
    private fun clearAllData() {
        try {
            listOf("translation_keys", "interactive_faces", "manakit").forEach { dir ->
                java.io.File(filesDir, dir).deleteRecursively()
            }
            Toast.makeText(this, getString(R.string.all_data_cleared), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not clear data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val PREFS_NAME         = "inyourface_prefs"
        const val PREF_HAPTIC        = "haptic_feedback"
        const val PREF_GRID_COORDS   = "show_grid_coords"
        const val PREF_AUTO_PRESCOUT = "auto_prescout"
    }
}
