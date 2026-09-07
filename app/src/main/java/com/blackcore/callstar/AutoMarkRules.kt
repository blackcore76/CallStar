package com.blackcore.callstar

import android.content.Context
import com.blackcore.callstar.data.AppPrefs

/**
 * 자동 중요 마킹 규칙 — 파일명 대조 방식(권한 0, 완전 오프라인).
 *
 * 갤럭시 통화녹음 파일명은 보통 `{상대}_YYMMDD_HHMMSS.m4a` 꼴이고,
 * `{상대}` 는 저장된 연락처 이름(큰형·누나) 또는 미저장 번호(114·16447900)다.
 * 사용자가 등록한 키워드를 이 앞부분과 대조해 매칭되면 중요 통화로 본다.
 * CALL_LOG/CONTACTS 같은 민감 권한을 전혀 쓰지 않는다.
 */
object AutoMarkRules {

    // 파일명 뒤쪽 날짜/시각 토큰(_YYMMDD_HHMMSS[.ext]) 제거용
    private val DATE_TAIL = Regex("_\\d{6,8}_\\d{6}(\\.[A-Za-z0-9]+)?$")

    /** 파일명에서 상대 식별 라벨(앞부분)만 뽑아낸다. */
    fun callerLabel(displayName: String): String {
        var s = displayName
            .removePrefix("통화 녹음 ")
            .removePrefix("통화 ")
        s = DATE_TAIL.replace(s, "")
        s = s.substringBeforeLast('.')   // 남은 확장자 방어
        return s.trim()
    }

    /**
     * 파일명이 등록 키워드와 매칭되면 그 키워드를 반환(안내 문구용), 아니면 null.
     * - 이름/문자열: 대소문자 무시 부분일치
     * - 숫자(번호): 구분기호 무시하고 4자리 이상일 때 숫자열 부분일치
     */
    fun matchedKeyword(keywords: List<String>, displayName: String): String? {
        if (keywords.isEmpty()) return null
        val label = callerLabel(displayName)
        val labelLc = label.lowercase()
        val labelDigits = label.filter { it.isDigit() }
        for (kw in keywords) {
            val k = kw.trim()
            if (k.isEmpty()) continue
            if (labelLc.contains(k.lowercase())) return kw
            val kDigits = k.filter { it.isDigit() }
            if (kDigits.length >= 4 && labelDigits.contains(kDigits)) return kw
        }
        return null
    }

    /** 이 통화가 자동 중요 대상인지(프리미엄 + 기능 켜짐 + 목록 매칭). 반환: 매칭 키워드 or null */
    fun autoKeepMatch(context: Context, displayName: String): String? {
        if (!AppPrefs.isPremium(context)) return null
        if (!AppPrefs.autoMarkEnabled(context)) return null
        return matchedKeyword(AppPrefs.autoMarkKeywords(context), displayName)
    }
}
