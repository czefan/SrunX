package io.github.czefan.srunx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.czefan.srunx.SrunXApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as? SrunXApp ?: return@launch
                val cfg = app.repository.configFlow.first()
                if (cfg.autoMonitorEnabled && cfg.autoStartOnBoot) {
                    runCatching { SrunMonitorService.start(appContext) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
