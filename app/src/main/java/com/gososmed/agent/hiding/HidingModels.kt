package com.gososmed.agent.hiding

/**
 * Representasi satu kunci setting sistem yang dipantau atau disembunyikan.
 * Diadopsi 1:1 dari Shizuku-Next SettingKey.
 */
data class SettingKey(
    val namespace: String,
    val name: String,
    val hiddenValue: String,
    val defaultVisibleValue: String? = null
) {
    val id: String get() = "$namespace/$name"
}

/**
 * Daftar sinyal sistem yang sering diperiksa oleh aplikasi perbankan, finansial, dan platform sosial
 * untuk menolak eksekusi (Diadopsi 1:1 dari Shizuku-Next Signal).
 */
enum class Signal(val keys: List<SettingKey>) {
    DEVELOPER_OPTIONS(
        listOf(SettingKey("global", "development_settings_enabled", "0", defaultVisibleValue = "1"))
    ),

    USB_DEBUGGING(
        listOf(SettingKey("global", "adb_enabled", "0", defaultVisibleValue = "1"))
    ),

    WIRELESS_DEBUGGING(
        listOf(SettingKey("global", "adb_wifi_enabled", "0", defaultVisibleValue = "1"))
    )
}

/**
 * Interface abstraksi penyimpanan nilai asli sebelum disembunyikan.
 * Memungkinkan 100% testabilitas di tingkat unit test JVM tanpa dependensi Android Context.
 */
interface HidingStore {
    fun saveOriginal(keyId: String, value: String)
    fun getOriginal(keyId: String): String?
    fun clearOriginal(keyId: String)
    fun getAllSavedKeys(): Set<String>
    fun clearAll()
}

/**
 * Implementasi in-memory untuk pengujian unit test JVM.
 */
class MemoryHidingStore : HidingStore {
    private val map = mutableMapOf<String, String>()

    override fun saveOriginal(keyId: String, value: String) {
        if (!map.containsKey(keyId)) map[keyId] = value
    }

    override fun getOriginal(keyId: String): String? = map[keyId]

    override fun clearOriginal(keyId: String) {
        map.remove(keyId)
    }

    override fun getAllSavedKeys(): Set<String> = map.keys.toSet()

    override fun clearAll() {
        map.clear()
    }
}
