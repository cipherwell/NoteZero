package com.cipherwell.notezero.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/**
 * Production DataStore instance: one preferences file per process, created lazily.
 * Preferences store only NON-secret UI settings (auto-lock minutes, theme mode); the VMK,
 * key envelopes and all content never touch this file.
 */
private val Context.noteZeroDataStore: DataStore<Preferences> by preferencesDataStore(name = "notezero-prefs")

/**
 * Typed, suspend-only access to the app's non-secret preferences.
 *
 * Kept as a small class (not a global) so tests can point it at an isolated file via
 * [createForFile] instead of sharing the production DataStore.
 */
class VaultPreferences(private val dataStore: DataStore<Preferences>) {

    val autoLockMinutes: Flow<Int> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> (preferences[AUTO_LOCK_MINUTES_KEY] ?: DEFAULT_AUTO_LOCK_MINUTES).coerceIn(MIN_AUTO_LOCK_MINUTES, MAX_AUTO_LOCK_MINUTES) }

    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> parseTheme(preferences[THEME_MODE_KEY]) }

    suspend fun setAutoLockMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[AUTO_LOCK_MINUTES_KEY] = minutes.coerceIn(MIN_AUTO_LOCK_MINUTES, MAX_AUTO_LOCK_MINUTES)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[THEME_MODE_KEY] = mode.name }
    }

    companion object {
        const val DEFAULT_AUTO_LOCK_MINUTES: Int = 5

        /** 0 disables the idle auto-lock timer. */
        const val MIN_AUTO_LOCK_MINUTES: Int = 0
        const val MAX_AUTO_LOCK_MINUTES: Int = 240

        private val AUTO_LOCK_MINUTES_KEY = intPreferencesKey("auto_lock_minutes")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

        private fun parseTheme(raw: String?): ThemeMode =
            ThemeMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ThemeMode.SYSTEM

        /** Production preferences backed by the app-private DataStore file. */
        fun create(context: Context): VaultPreferences =
            VaultPreferences(context.applicationContext.noteZeroDataStore)

        /** Test/isolated preferences backed by an explicit file (never the production file). */
        fun createForFile(
            file: File,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): VaultPreferences = VaultPreferences(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
    }
}
