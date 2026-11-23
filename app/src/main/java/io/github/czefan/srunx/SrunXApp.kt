package io.github.czefan.srunx

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import io.github.czefan.srunx.data.AuthCoordinator
import io.github.czefan.srunx.data.SettingsRepository

class SrunXApp : Application() {
    private var _repository: SettingsRepository? = null
    private var _coordinator: AuthCoordinator? = null

    val repository: SettingsRepository
        get() = _repository ?: error("SettingsRepository 仅限在 :bg 后台进程访问")

    val coordinator: AuthCoordinator
        get() = _coordinator ?: error("AuthCoordinator 仅限在 :bg 后台进程访问")

    override fun onCreate() {
        super.onCreate()
        if (isBgProcess()) {
            val repo = SettingsRepository(this)
            val coord = AuthCoordinator(this, repo)
            coord.ensureNetworkCallbackRegistered()
            _repository = repo
            _coordinator = coord
        }
    }

    private fun isBgProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val pid = Process.myPid()
            val am = getSystemService(ActivityManager::class.java)
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName.orEmpty()
        }
        return processName.endsWith(":bg")
    }
}
