package com.lastwave.app.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Status callback for the in-app update's PackageInstaller session. */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {

    @Inject
    lateinit var updateManager: AppUpdateManager

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system's "Update this app?" confirmation.
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        updateManager.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
