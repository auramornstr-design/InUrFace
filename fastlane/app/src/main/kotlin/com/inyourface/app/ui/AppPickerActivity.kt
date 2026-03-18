/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * AppPickerActivity.kt
 * Lets the user pick any installed app to create an overlay for.
 * Loads the full app list on a background thread to keep UI responsive.
 * Search filters live as the user types.
 * Returns the selected package name to MainActivity via setResult.
 */

package com.inyourface.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.inyourface.app.R
import com.inyourface.app.model.AppInfo
import com.inyourface.app.ui.adapter.AppPickerAdapter
import kotlinx.coroutines.*
import java.io.File

class AppPickerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var searchInput: TextInputEditText
    private lateinit var switchSystem: SwitchMaterial
    private lateinit var progressBar: ProgressBar
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppPickerAdapter

    private var showSystemApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        searchInput = findViewById(R.id.searchInput)
        switchSystem = findViewById(R.id.switchSystemApps)
        progressBar = findViewById(R.id.progressBar)
        recycler = findViewById(R.id.recyclerApps)

        adapter = AppPickerAdapter { app -> returnResult(app) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Search watcher
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // System apps toggle
        switchSystem.setOnCheckedChangeListener { _, checked ->
            showSystemApps = checked
            loadApps()
        }

        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        recycler.visibility = View.GONE

        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppInfo.loadInstalled(this@AppPickerActivity, includeSystem = showSystemApps)
            }

            val configuredPackages = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "translation_keys")
                if (!dir.exists()) emptySet()
                else dir.listFiles { f -> f.extension == "json" }
                    ?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
            }

            adapter.submitList(apps, configuredPackages)

            progressBar.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun returnResult(app: AppInfo) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(RESULT_PACKAGE, app.packageName)
                putExtra(RESULT_LABEL, app.label)
            }
        )
        finish()
    }

    companion object {
        const val RESULT_PACKAGE = "result_package"
        const val RESULT_LABEL = "result_label"
    }
}
