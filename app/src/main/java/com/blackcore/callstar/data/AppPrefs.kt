package com.blackcore.callstar.data

import android.content.Context

/**
 * 로컬 설정: 프리미엄 여부(임시, 나중에 Play Billing 으로 대체), 백업 폴더 SAF 트리 URI.
 */
object AppPrefs {
    private const val PREFS = "callstar_prefs"
    private const val K_PREMIUM = "premium"
    private const val K_BACKUP_TREE = "backup_tree_uri"
    private const val K_THEME = "theme_mode"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPremium(context: Context): Boolean = sp(context).getBoolean(K_PREMIUM, false)
    fun setPremium(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(K_PREMIUM, value).apply()

    fun backupTreeUri(context: Context): String? = sp(context).getString(K_BACKUP_TREE, null)
    fun setBackupTreeUri(context: Context, value: String?) =
        sp(context).edit().putString(K_BACKUP_TREE, value).apply()

    private const val K_POSTCALL_OVERLAY = "postcall_overlay"

    /** 통화 후 방식: false=알림(기본), true=오버레이 팝업 */
    fun postCallOverlay(context: Context): Boolean = sp(context).getBoolean(K_POSTCALL_OVERLAY, false)
    fun setPostCallOverlay(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(K_POSTCALL_OVERLAY, value).apply()

    /** 테마 모드 키: "system" | "light" | "dark" */
    fun themeModeKey(context: Context): String = sp(context).getString(K_THEME, "system") ?: "system"
    fun setThemeModeKey(context: Context, key: String) =
        sp(context).edit().putString(K_THEME, key).apply()
}
