package com.opensolr.pdf.update

import android.content.Context

/** The Google Play build has no network at all: Google Play updates it. */
object Updates {

    data class Update(val version: String, val apkUrl: String)

    sealed class Outcome {
        object Started : Outcome()
        object NeedsPermission : Outcome()
        object Invalid : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    const val AVAILABLE = false

    suspend fun check(): Result<Update?> = Result.success(null)
    fun fromStore(context: Context): Boolean = true
    fun canInstall(context: Context): Boolean = false
    fun openInstallPermission(context: Context) {}
    suspend fun install(context: Context, update: Update, onProgress: (Int) -> Unit): Outcome = Outcome.Failed("")
}
