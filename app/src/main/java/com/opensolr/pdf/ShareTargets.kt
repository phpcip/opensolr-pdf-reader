package com.opensolr.pdf

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build

/** Where a PDF can be shared in one tap: Add to Dropbox first, then the two apps it was shared to most often. */
object ShareTargets {

    class Target(val component: ComponentName, val label: String, val icon: Drawable)

    private const val DROPBOX = "com.dropbox.android"
    private const val DROPBOX_SEND = "com.dropbox.android.activity.DropboxSendTo"
    private const val PREFS = "share_counts"

    private fun sendIntent() = Intent(Intent.ACTION_SEND).setType("application/pdf")

    private fun handlers(context: Context) = context.packageManager.let { pm ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.queryIntentActivities(sendIntent(), PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(sendIntent(), 0)
    }

    /** Add to Dropbox: the Dropbox app's own upload screen, whenever the Dropbox app is on the phone. */
    fun dropbox(context: Context): Target? {
        val pm = context.packageManager
        val info = handlers(context).filter { it.activityInfo.packageName == DROPBOX }
            .let { list -> list.firstOrNull { it.activityInfo.name == DROPBOX_SEND } ?: list.firstOrNull() } ?: return null
        return Target(ComponentName(info.activityInfo.packageName, info.activityInfo.name), info.loadLabel(pm).toString(), info.loadIcon(pm))
    }

    /** The two apps PDFs were shared to most often from here, Dropbox aside. */
    fun top(context: Context): List<Target> {
        val pm = context.packageManager
        val counts = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        return handlers(context)
            .filter { it.activityInfo.packageName != DROPBOX }
            .map { it to ((counts[ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString()] as? Int) ?: 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(2)
            .map { (info, _) -> Target(ComponentName(info.activityInfo.packageName, info.activityInfo.name), info.loadLabel(pm).toString(), info.loadIcon(pm)) }
    }

    fun record(context: Context, component: ComponentName) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = component.flattenToString()
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun send(uri: Uri) = sendIntent()
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .also { it.clipData = android.content.ClipData.newRawUri("", uri) }

    /** Straight to one app, counted like a pick in the chooser. */
    fun sendTo(context: Context, uri: Uri, target: Target) {
        if (target.component.packageName != DROPBOX) record(context, target.component)
        context.startActivity(send(uri).setComponent(target.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The system's share sheet; the app picked in it is counted for the row of shortcuts. */
    fun chooser(context: Context, uri: Uri, title: String) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, 0, Intent(context, Chosen::class.java), flags)
        context.startActivity(Intent.createChooser(send(uri), title, pending.intentSender).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    class Chosen : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val component: ComponentName? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
            }
            if (component != null && component.packageName != DROPBOX) record(context, component)
        }
    }
}
