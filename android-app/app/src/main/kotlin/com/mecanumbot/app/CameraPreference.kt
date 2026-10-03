package com.mecanumbot.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mecanumbot.camera.Lens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class CameraOptions(val lens: Lens = Lens.ULTRA_WIDE, val rotation: Int = 0)

/** Camera lens and mount rotation (spec §5). Defaults: ultra-wide, 0°. */
class CameraPreference(context: Context) {
    private val store = context.applicationContext.settings
    private val lensKey = stringPreferencesKey("camera_lens")
    private val rotationKey = intPreferencesKey("camera_rotation")

    val options: Flow<CameraOptions> = store.data.map { p ->
        CameraOptions(
            lens = p[lensKey]?.let { runCatching { Lens.valueOf(it) }.getOrNull() } ?: Lens.ULTRA_WIDE,
            rotation = p[rotationKey]?.takeIf { it in ROTATIONS } ?: 0,
        )
    }.distinctUntilChanged()

    suspend fun save(options: CameraOptions) {
        store.edit {
            it[lensKey] = options.lens.name
            it[rotationKey] = options.rotation
        }
    }

    companion object {
        val ROTATIONS = listOf(0, 90, 180, 270)
    }
}
