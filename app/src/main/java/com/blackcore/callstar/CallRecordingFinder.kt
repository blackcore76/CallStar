package com.blackcore.callstar

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * 통화녹음 파일 조회 유틸.
 *
 * 갤럭시 기본 통화녹음은 보통 다음 위치에 저장된다:
 *   /storage/emulated/0/Recordings/Call/
 * MediaStore 기준으로는 RELATIVE_PATH 가 "Recordings/Call/" 로 시작.
 *
 * 단, 기기/원스토리지 정책/OneUI 버전에 따라 "Sounds/" 나 "Call/" 등
 * 다른 상대경로일 수 있어, 매칭 후보를 여러 개 둔다.
 */
data class CallRecording(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val uri: Uri,
)

object CallRecordingFinder {

    private const val TAG = "CallStar/Finder"

    /**
     * 통화녹음 상대경로 접두어 후보 (소문자, startsWith 로 비교).
     * 넓은 폴백("recordings/")은 음성메모까지 긁어와서 제외 — 통화 폴더만 정확히.
     */
    private val PATH_PREFIXES = listOf(
        "recordings/call/",   // OneUI 5+ 표준(갤럭시)
        "sounds/call/",       // 일부 기기
        "call/",              // 드문 변형
    )

    private val AUDIO_COLLECTION: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * 통화녹음 폴더에서 가장 최근 파일을 반환. 없으면 null.
     * 최신 판정은 DATE_MODIFIED(초) 우선, 동률이면 _ID(큰 값 = 최신).
     */
    fun findLatestCallRecording(context: Context): CallRecording? {
        val all = queryCallRecordings(context, limit = 1)
        return all.firstOrNull()
    }

    /** 파일명이 정확히 일치하는 통화녹음 원본을 찾는다(백업본 → 원본 역추적용). 없으면 null. */
    fun findByDisplayName(context: Context, name: String): CallRecording? =
        queryCallRecordings(context).firstOrNull { it.displayName == name }

    /**
     * 통화녹음 목록을 최신순으로 반환.
     * @param limit 0 이하이면 제한 없음.
     */
    fun queryCallRecordings(context: Context, limit: Int = 0): List<CallRecording> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )

        // RELATIVE_PATH LIKE 'Recordings/Call/%' OR ... (대소문자 무시 위해 앱단에서 재필터)
        val likeClauses = PATH_PREFIXES.joinToString(" OR ") {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        }
        val selection = "($likeClauses)"
        // startsWith 를 위해 접두어 뒤에만 % (앞에는 안 붙임)
        val args = PATH_PREFIXES.map { "$it%" }.toTypedArray()

        val sortOrder =
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC, ${MediaStore.Audio.Media._ID} DESC"

        val results = ArrayList<CallRecording>()
        try {
            context.contentResolver.query(
                AUDIO_COLLECTION, projection, selection, args, sortOrder
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (c.moveToNext()) {
                    val path = c.getString(pathCol) ?: continue
                    // 대소문자 무시 + 접두어 정확 매칭(startsWith)
                    val pl = path.lowercase()
                    val matched = PATH_PREFIXES.any { pl.startsWith(it) }
                    if (!matched) continue

                    val id = c.getLong(idCol)
                    val rec = CallRecording(
                        id = id,
                        displayName = c.getString(nameCol) ?: "(이름없음)",
                        relativePath = path,
                        dateAddedSec = c.getLong(addedCol),
                        dateModifiedSec = c.getLong(modCol),
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        uri = ContentUris.withAppendedId(AUDIO_COLLECTION, id),
                    )
                    results.add(rec)
                    if (limit > 0 && results.size >= limit) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query 실패", e)
        }
        Log.d(TAG, "통화녹음 ${results.size}건 조회 (limit=$limit)")
        return results
    }
}
