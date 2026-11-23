package io.github.czefan.srunx.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.czefan.srunx.protocol.AuthLogEntry
import io.github.czefan.srunx.protocol.SrunConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.srunDataStore: DataStore<Preferences> by preferencesDataStore(name = "srunx_prefs")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val HOST = stringPreferencesKey("host")
        val AC_ID = stringPreferencesKey("ac_id")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD_ENC = stringPreferencesKey("password_enc")
        val DOMAIN_SUFFIX = stringPreferencesKey("domain_suffix")
        val AUTO_MONITOR = booleanPreferencesKey("auto_monitor")
        val AUTO_START_BOOT = booleanPreferencesKey("auto_start_boot")
        val LOGS_JSON = stringPreferencesKey("logs_json")
    }

    private val prefsFlow: Flow<Preferences> = context.srunDataStore.data.catch { err ->
        if (err is IOException) emit(emptyPreferences()) else throw err
    }

    val configFlow: Flow<SrunConfig> = prefsFlow.map { prefs ->
        SrunConfig(
            host = prefs[Keys.HOST].orEmpty(),
            acId = prefs[Keys.AC_ID].orEmpty(),
            username = prefs[Keys.USERNAME].orEmpty(),
            password = KeystoreCrypto.decrypt(prefs[Keys.PASSWORD_ENC].orEmpty()),
            domainSuffix = prefs[Keys.DOMAIN_SUFFIX].orEmpty(),
            autoMonitorEnabled = prefs[Keys.AUTO_MONITOR] ?: true,
            autoStartOnBoot = prefs[Keys.AUTO_START_BOOT] ?: true,
        )
    }

    val logsFlow: Flow<List<AuthLogEntry>> = prefsFlow.map { prefs ->
        decodeLogs(prefs[Keys.LOGS_JSON].orEmpty())
    }

    suspend fun saveConfig(config: SrunConfig) {
        val normalizedHost = config.normalizedHost()
        val encryptedPwd = KeystoreCrypto.encrypt(config.password)
        context.srunDataStore.edit { prefs ->
            prefs[Keys.HOST] = normalizedHost
            prefs[Keys.AC_ID] = config.acId.trim()
            prefs[Keys.USERNAME] = config.username.trim()
            prefs[Keys.PASSWORD_ENC] = encryptedPwd
            prefs[Keys.DOMAIN_SUFFIX] = config.domainSuffix.trim().removePrefix("@")
        }
    }

    suspend fun setAutoMonitor(enabled: Boolean) {
        context.srunDataStore.edit { prefs ->
            prefs[Keys.AUTO_MONITOR] = enabled
        }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.srunDataStore.edit { prefs ->
            prefs[Keys.AUTO_START_BOOT] = enabled
        }
    }

    suspend fun appendLog(entry: AuthLogEntry) {
        context.srunDataStore.edit { prefs ->
            val current = decodeLogs(prefs[Keys.LOGS_JSON].orEmpty())
            val updated = (listOf(entry) + current).take(MAX_LOGS)
            prefs[Keys.LOGS_JSON] = encodeLogs(updated)
        }
    }

    suspend fun clearLogs() {
        context.srunDataStore.edit { prefs ->
            prefs.remove(Keys.LOGS_JSON)
        }
    }

    private fun decodeLogs(raw: String): List<AuthLogEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        AuthLogEntry(
                            timestampMs = obj.optLong("ts", 0L),
                            trigger = obj.optString("trigger", ""),
                            success = obj.optBoolean("ok", false),
                            message = obj.optString("msg", ""),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeLogs(entries: List<AuthLogEntry>): String {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject().apply {
                    put("ts", entry.timestampMs)
                    put("trigger", entry.trigger)
                    put("ok", entry.success)
                    put("msg", entry.message)
                },
            )
        }
        return arr.toString()
    }

    companion object {
        const val MAX_LOGS = 20
    }
}
