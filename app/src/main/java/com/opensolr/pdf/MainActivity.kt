package com.opensolr.pdf

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.opensolr.pdf.doc.PdfDoc
import com.opensolr.pdf.ui.App
import com.opensolr.pdf.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    /** The document on screen, or null for the start screen. */
    var doc by mutableStateOf<PdfDoc?>(null)
        private set
    var name by mutableStateOf("")
        private set
    var error by mutableStateOf<Int?>(null)
    var update by mutableStateOf<Updates.Update?>(null)
    var updateProgress by mutableStateOf<Int?>(null)
    var updateMessage by mutableStateOf<Int?>(null)
    var checking by mutableStateOf(false)

    private var uri: Uri? = null

    /** The app that sent the PDF (its name for the back button), or null when it was picked here. */
    var caller by mutableStateOf<String?>(null)
        private set
    private var fromAnotherApp = false

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked -> if (picked != null) open(picked) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(this) }
        handle(intent)
        checkForUpdate(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (data != null) {
            fromAnotherApp = true
            caller = callerName()
            open(data)
        }
    }

    private fun callerName(): String? = try {
        val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host
        if (pkg == null || pkg == packageName) null
        else packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }

    /** Back from a PDF: to the app it came from, or to the start screen when it was picked here. */
    fun back() {
        if (fromAnotherApp) finish() else closeDoc()
    }

    fun pickFile() {
        fromAnotherApp = false
        caller = null
        pick.launch(arrayOf("application/pdf"))
    }

    private fun open(target: Uri) {
        error = null
        lifecycleScope.launch {
            try {
                val opened = PdfDoc.open(this@MainActivity, target)
                doc?.close()
                uri = target
                name = displayName(target)
                doc = opened
            } catch (e: PdfDoc.PasswordProtected) {
                error = R.string.err_password
            } catch (e: Exception) {
                error = R.string.err_open
            }
        }
    }

    fun closeDoc() {
        doc?.close()
        doc = null
        uri = null
    }

    private fun displayName(target: Uri): String {
        val fromProvider = try {
            contentResolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        val raw = fromProvider ?: target.lastPathSegment ?: "document.pdf"
        val clean = raw.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.pdf" }
        return if (clean.endsWith(".pdf", true)) clean else "$clean.pdf"
    }

    /** A copy of the PDF in the app's cache, under its own name, which any app it is shared to can read. */
    suspend fun shareUri(): Uri? = withContext(Dispatchers.IO) {
        val source = uri ?: return@withContext null
        try {
            val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, name.ifBlank { "document.pdf" })
            contentResolver.openInputStream(source)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@withContext null
            FileProvider.getUriForFile(this@MainActivity, "$packageName.share", out)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- updates ----------------

    /** Once a day on start, from the GitHub releases; a copy from a store is updated by that store. */
    fun checkForUpdate(now: Boolean) {
        if (!Updates.AVAILABLE || Updates.fromStore(this)) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!now && System.currentTimeMillis() - prefs.getLong("last_update_check", 0L) < 24 * 3600_000L) return
        if (checking) return
        checking = true
        updateMessage = null
        lifecycleScope.launch {
            val r = Updates.check()
            checking = false
            if (r.isSuccess) prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            val u = r.getOrNull()
            if (u != null) update = u
            else if (now) updateMessage = if (r.isFailure) R.string.update_failed else R.string.up_to_date
        }
    }

    fun installUpdate() {
        val u = update ?: return
        if (updateProgress != null) return
        if (!Updates.canInstall(this)) {
            updateMessage = R.string.update_allow
            Updates.openInstallPermission(this)
            return
        }
        updateProgress = 0
        updateMessage = null
        lifecycleScope.launch {
            val outcome = Updates.install(applicationContext, u) { pct -> updateProgress = pct }
            updateProgress = null
            updateMessage = when (outcome) {
                is Updates.Outcome.Started -> null
                is Updates.Outcome.NeedsPermission -> R.string.update_allow
                else -> R.string.update_failed
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) doc?.close()
    }
}
