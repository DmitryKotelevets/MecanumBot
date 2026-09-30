package com.mecanumbot.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

enum class LinkKind { USB, FAKE }

private val Context.settings by preferencesDataStore(name = "settings")

/** The USB / Fake choice (spec §9). Default USB; no automatic fallback. */
class LinkPreference(context: Context) {
    private val store = context.applicationContext.settings
    private val key = stringPreferencesKey("link")

    suspend fun load(): LinkKind =
        store.data.first()[key]?.let { runCatching { LinkKind.valueOf(it) }.getOrNull() } ?: LinkKind.USB

    suspend fun save(kind: LinkKind) {
        store.edit { it[key] = kind.name }
    }
}
