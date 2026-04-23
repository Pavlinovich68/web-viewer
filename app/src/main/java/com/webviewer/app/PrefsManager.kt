package com.webviewer.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Управление настройками приложения и хранением авторизационных данных.
 * Использует EncryptedSharedPreferences для безопасного хранения.
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = createSecurePrefs(context)

    /** URL сайта */
    var siteUrl: String?
        get() = prefs.getString(KEY_SITE_URL, null)
        set(value) = prefs.edit().putString(KEY_SITE_URL, value).apply()

    /** Сохранённые cookies */
    var cookies: String?
        get() = securePrefs.getString(KEY_COOKIES, null)
        set(value) = securePrefs.edit().putString(KEY_COOKIES, value).apply()

    /** Имя пользователя */
    var username: String?
        get() = securePrefs.getString(KEY_USERNAME, null)
        set(value) = securePrefs.edit().putString(KEY_USERNAME, value).apply()

    /** Пароль */
    var password: String?
        get() = securePrefs.getString(KEY_PASSWORD, null)
        set(value) = securePrefs.edit().putString(KEY_PASSWORD, value).apply()

    /** Правила блокировки pop-up окон */
    var popupBlockRules: String
        get() = prefs.getString(KEY_POPUP_BLOCK_RULES, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_POPUP_BLOCK_RULES, value).apply()

    /** Первый запуск (URL ещё не задан) */
    val isFirstLaunch: Boolean
        get() = siteUrl.isNullOrBlank()

    /** Очистить все сохранённые данные */
    fun clearAll() {
        securePrefs.edit().clear().apply()
        prefs.edit()
            .remove(KEY_SITE_URL)
            .remove(KEY_POPUP_BLOCK_RULES)
            .apply()
    }

    /** Очистить только авторизационные данные */
    fun clearAuthData() {
        securePrefs.edit().clear().apply()
    }

    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (error: Exception) {
            // Fallback keeps the app usable on devices where Android Keystore is unavailable.
            Log.e(TAG, "Falling back to unencrypted preferences", error)
            context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "PrefsManager"
        private const val KEY_SITE_URL = "site_url"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_POPUP_BLOCK_RULES = "popup_block_rules"
    }
}
