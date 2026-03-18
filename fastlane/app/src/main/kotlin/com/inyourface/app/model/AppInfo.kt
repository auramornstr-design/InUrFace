/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * AppInfo.kt
 * Lightweight metadata about an installed app.
 * Used by AppPickerActivity to show the list of apps available to overlay.
 * Does not carry full package metadata — just what's needed for display and selection.
 */

package com.inyourface.app.model

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isSystemApp: Boolean,
    val hasExistingProfile: Boolean = false
) {
    fun icon(context: Context): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) { null }

    companion object {

        /**
         * Load all user-installed apps on the device.
         * Excludes system apps unless [includeSystem] is true.
         * Excludes our own package — no point overlaying ourselves.
         */
        fun loadInstalled(context: Context, includeSystem: Boolean = false): List<AppInfo> {
            val pm = context.packageManager
            val ownPackage = context.packageName

            return pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    app.packageName != ownPackage && (includeSystem || !isSystem)
                }
                .mapNotNull { app ->
                    try {
                        val label = pm.getApplicationLabel(app).toString()
                        val versionName = try {
                            pm.getPackageInfo(app.packageName, 0).versionName ?: ""
                        } catch (e: Exception) { "" }

                        AppInfo(
                            packageName = app.packageName,
                            label = label,
                            versionName = versionName,
                            isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (e: Exception) { null }
                }
                .sortedBy { it.label.lowercase() }
        }
    }
}
