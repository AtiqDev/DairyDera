package com.example.dairypos

import DatabaseHelper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.*
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class DatabaseManagerActivity : AppCompatActivity() {

    private lateinit var txtDbInfo: TextView
    private lateinit var txtTimer: TextView
    private lateinit var editLog: EditText
    private lateinit var btnImport: Button
    private lateinit var btnExport: Button
    private lateinit var btnStart: Button
    private lateinit var btnDeleteExports: Button
    private val dbName = "DairyFarmPOS.db"
    private val dbPath: File by lazy { applicationContext.getDatabasePath(dbName) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var dbHelper: DatabaseHelper? = null

    // ✅ CountDownTimer at class level
    private var countDownTimer: CountDownTimer? = null

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { importDatabase(it) } ?: log("❌ No file selected.")
        }

    // ==========================================================
    // Lifecycle
    // ==========================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_database_manager)

        txtDbInfo = findViewById(R.id.txtDbInfo)
        txtTimer = findViewById(R.id.txtTimer)
        editLog = findViewById(R.id.editLog)
        btnImport = findViewById(R.id.btnImport)
        btnExport = findViewById(R.id.btnExport)
        btnStart = findViewById(R.id.btnStart)
        btnDeleteExports = findViewById(R.id.btnDeleteExports)

        refreshDbInfo()

        btnImport.setOnClickListener {
            cancelCountdown()
            importPicker.launch("*/*")
        }

        btnDeleteExports.setOnClickListener {
            cancelCountdown()
            deleteExportedFiles()
        }

        btnExport.setOnClickListener {
            cancelCountdown()
            exportDatabase()
        }

        btnStart.setOnClickListener {
            cancelCountdown()
            launchMainApp()
        }

        startCountdown()

        if (dbPath.exists()) {
            DBFileLock.lock.lock()
            try {
                dbHelper = DatabaseHelper(this)
            } finally {
                DBFileLock.lock.unlock()
            }
            logActivity("DBMGR_APP_START", "System", extra = "DatabaseManagerActivity opened")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
        cancelCountdown()
        DBFileLock.lock.lock()
        try {
            try { dbHelper?.close() } catch (_: Exception) {}
            dbHelper = null
        } finally {
            DBFileLock.lock.unlock()
        }
    }

    // ==========================================================
    // Countdown
    // ==========================================================
    private fun startCountdown() {
        cancelCountdown() // ensure no multiple timers
        countDownTimer = object : CountDownTimer(10L, 10L) {
            override fun onTick(ms: Long) {
                txtTimer.text = "Starting in ${ms / 1000}..."
            }

            override fun onFinish() {
                txtTimer.text = "Launching..."
                logActivity("DBMGR_AUTO_LAUNCH", "System", extra = "Timer elapsed, launching main app")
                launchMainApp()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
        txtTimer.text = "⏸ Auto-start cancelled"
    }

    // ==========================================================
    // Database Info
    // ==========================================================
    private fun refreshDbInfo() {
        try {
            if (!dbPath.exists()) {
                txtDbInfo.text = "❌ DB file not found.\nPath: ${dbPath.absolutePath}"
                btnExport.isEnabled = false
                return
            }

            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val lastModified = fmt.format(Date(dbPath.lastModified()))
            var createdStr = "N/A"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val attrs = Files.readAttributes(dbPath.toPath(), BasicFileAttributes::class.java)
                    createdStr = fmt.format(Date(attrs.creationTime().toMillis()))
                } catch (_: Exception) {
                    createdStr = lastModified
                }
            } else {
                createdStr = lastModified
            }

            txtDbInfo.text = buildString {
                append("📁 Path: ${dbPath.absolutePath}\n")
                append("🕒 Created: $createdStr\n")
                append("🕓 Last Modified: $lastModified")
            }

            btnExport.isEnabled = true
            logActivity("DBMGR_REFRESH_INFO", "Database", extra = "Refreshed DB info")
        } catch (e: Exception) {
            log("⚠️ Failed to read DB info: ${e.message}")
            btnExport.isEnabled = false
        }
    }

    // ==========================================================
    // Database Import
    // ==========================================================
    private fun importDatabase(uri: Uri) {
        log("Starting import from: $uri")

        ioExecutor.execute {
            DBFileLock.lock.lock()
            try {
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open selected file")

                    dbPath.parentFile?.mkdirs()
                    try { dbHelper?.close() } catch (_: Exception) {}
                    dbHelper = null

                    if (dbPath.exists()) {
                        val backupFile = File(
                            dbPath.parent,
                            "DairyFarmPOS_backup_${System.currentTimeMillis()}.db"
                        )
                        dbPath.copyTo(backupFile, overwrite = true)
                        log("📦 Backup created: ${backupFile.name}")
                    }

                    input.use { inp -> dbPath.outputStream().use { out -> inp.copyTo(out) } }

                    dbHelper = DatabaseHelper(this@DatabaseManagerActivity)

                    runOnUiThread {
                        log("✅ Import successful.")
                        refreshDbInfo()
                        logActivity("DBMGR_IMPORT_SUCCESS", "Database", extra = "Imported from: $uri")
                        launchMainApp()
                    }
                } catch (ex: Exception) {
                    val sw = StringWriter().also { ex.printStackTrace(PrintWriter(it)) }
                    runOnUiThread {
                        log("❌ Import failed: ${ex.message}")
                        log(sw.toString())
                        logActivity("DBMGR_IMPORT_FAIL", "Database", extra = ex.message)
                    }
                }
            } finally {
                DBFileLock.lock.unlock()
            }
        }
    }

    // ==========================================================
    // Database Export / Share
    // ==========================================================
    private fun exportDatabase() {
        if (!dbPath.exists()) {
            log("❌ Export failed: Database does not exist.")
            btnExport.isEnabled = false
            return
        }

        ioExecutor.execute {
            var outFile: File? = null
            DBFileLock.lock.lock()
            try {
                try {
                    try { dbHelper?.close() } catch (_: Exception) {}
                    dbHelper = null

                    val exportDir = getExternalFilesDir(null) ?: filesDir
                    outFile = File(exportDir, "DairyFarmPOS_export_${System.currentTimeMillis()}.db")
                    dbPath.copyTo(outFile, overwrite = true)
                } finally {
                    if (dbPath.exists()) dbHelper = DatabaseHelper(this@DatabaseManagerActivity)
                }
            } finally {
                DBFileLock.lock.unlock()
            }

            runOnUiThread {
                try {
                    val file = outFile ?: throw IOException("Export file missing")
                    val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share database via"))
                    log("✅ Exported successfully: ${file.absolutePath}")
                    logActivity("DBMGR_EXPORT_SUCCESS", "Database", extra = "Exported: ${file.name}")
                } catch (e: Exception) {
                    log("❌ Failed to share export: ${e.message}")
                    logActivity("DBMGR_EXPORT_FAIL", "Database", extra = e.message)
                }
            }
        }
    }

    private fun deleteExportedFiles() {
        ioExecutor.execute {
            try {
                val exportDir = getExternalFilesDir(null) ?: filesDir
                val deleted = exportDir.listFiles()
                    ?.filter { it.name.startsWith("DairyFarmPOS_export_") && it.name.endsWith(".db") }
                    ?.count {
                        val result = it.delete()
                        if (result) log("🗑 Deleted: ${it.name}")
                        result
                    } ?: 0

                runOnUiThread {
                    if (deleted > 0) {
                        log("✅ Deleted $deleted exported file(s).")
                        logActivity("DBMGR_DELETE_EXPORTS", "Database", extra = "Deleted $deleted exported DBs")
                    } else {
                        log("ℹ️ No exported files found to delete.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    log("❌ Failed to delete exports: ${e.message}")
                    logActivity("DBMGR_DELETE_EXPORTS_FAIL", "Database", extra = e.message)
                }
            }
        }
    }
    // ==========================================================
    // Utility Logging
    // ==========================================================
    private fun log(message: String) {
        runOnUiThread {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            editLog.append("[$ts] $message\n")
        }
    }

    private fun logActivity(action: String, entity: String, entityId: Int = 0, extra: String? = null) {
        if (dbHelper == null || !dbPath.exists()) return
        try {
            val record = ActivityRecord(
                userId = 1,
                action = action,
                entity = entity,
                entityId = entityId,
                extra = extra
            )
            ActivityLogger.logActivity(this, dbHelper!!, record)
        } catch (e: Exception) {
            log("⚠️ Failed to log activity: ${e.message}")
        }
    }

    private fun launchMainApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            log("🚀 MainActivity launched; startup screen cleared.")
            logActivity("DBMGR_LAUNCH_MAIN", "System", extra = "MainActivity launched manually")
        } catch (e: Exception) {
            log("❌ Failed to launch MainActivity: ${e.message}")
            logActivity("DBMGR_LAUNCH_FAIL", "System", extra = e.message)
        }
    }
}
