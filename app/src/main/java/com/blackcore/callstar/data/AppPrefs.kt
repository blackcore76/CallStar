package com.blackcore.callstar.data

import android.content.Context
import org.json.JSONArray

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

    private const val K_HL_ENABLED = "hl_enabled"
    private const val K_HL_SHOWN = "hl_shown_count"

    // 알림 타고 들어왔을 때 대기 통화를 강조해주는 자동 안내 횟수(이 횟수까지만).
    const val HL_AUTO_MAX = 2

    /** 알림 안내 강조 사용 여부(설정에서 켜고 끔). 기본 켜짐. */
    fun highlightEnabled(context: Context): Boolean = sp(context).getBoolean(K_HL_ENABLED, true)
    fun setHighlightEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(K_HL_ENABLED, value).apply()

    /** 지금까지 강조 안내가 표시된 횟수. */
    fun highlightShownCount(context: Context): Int = sp(context).getInt(K_HL_SHOWN, 0)
    fun bumpHighlightShownCount(context: Context) =
        sp(context).edit().putInt(K_HL_SHOWN, highlightShownCount(context) + 1).apply()

    /** 설정에서 "다시 보기" — 강조 안내 카운터 리셋. */
    fun resetHighlightShownCount(context: Context) =
        sp(context).edit().putInt(K_HL_SHOWN, 0).apply()

    // ── 자동 중요 마킹(프리미엄): 특정 이름/번호 키워드 목록 ──────────────

    private const val K_AUTOMARK_ENABLED = "automark_enabled"
    private const val K_AUTOMARK_LIST = "automark_keywords"

    /** 자동 중요 마킹 사용 여부(기본 켜짐; 실제 동작은 프리미엄 + 목록 있을 때). */
    fun autoMarkEnabled(context: Context): Boolean = sp(context).getBoolean(K_AUTOMARK_ENABLED, true)
    fun setAutoMarkEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(K_AUTOMARK_ENABLED, value).apply()

    /** 자동 중요로 처리할 이름/번호 키워드 목록. */
    fun autoMarkKeywords(context: Context): List<String> {
        val raw = sp(context).getString(K_AUTOMARK_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setAutoMarkKeywords(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp(context).edit().putString(K_AUTOMARK_LIST, arr.toString()).apply()
    }

    /** 키워드 추가(공백 정리 + 중복/빈값 제거). 반환: 갱신된 목록. */
    fun addAutoMarkKeyword(context: Context, keyword: String): List<String> {
        val k = keyword.trim()
        if (k.isEmpty()) return autoMarkKeywords(context)
        val cur = autoMarkKeywords(context).toMutableList()
        if (cur.none { it.equals(k, ignoreCase = true) }) cur.add(k)
        setAutoMarkKeywords(context, cur)
        return cur
    }

    fun removeAutoMarkKeyword(context: Context, keyword: String): List<String> {
        val cur = autoMarkKeywords(context).filterNot { it.equals(keyword, ignoreCase = true) }
        setAutoMarkKeywords(context, cur)
        return cur
    }
}
