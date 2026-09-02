package com.blackcore.callstar.data

import android.content.Context
import android.util.Log
import com.blackcore.callstar.CallRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 별점 저장 진입점. 오버레이/미리보기 클릭 콜백이 여기로 온다.
 * DB 쓰기는 IO 스레드에서 (오버레이 클릭은 메인 스레드이므로).
 */
object RatingStore {

    private const val TAG = "CallStar/Store"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 오버레이용: 결과를 기다리지 않는 저장(별도 스코프). */
    fun save(context: Context, recording: CallRecording, rating: Int) {
        scope.launch { saveNow(context, recording, rating) }
    }

    /** 목록용: 저장 완료를 기다리는 버전(호출부에서 refresh 하기 위함). */
    suspend fun saveNow(context: Context, recording: CallRecording, rating: Int): Boolean {
        val app = context.applicationContext
        // _ID 가 유효할 때만 저장 (미리보기 샘플의 id=-1 은 건너뜀)
        if (recording.id <= 0L) {
            Log.d(TAG, "샘플(id=${recording.id}) → 저장 생략")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                AppDatabase.get(app).ratingDao().upsert(
                    RatingEntity(
                        mediastoreId = recording.id,
                        rating = rating,
                        displayName = recording.displayName,
                        ratedAt = System.currentTimeMillis(),
                    )
                )
                Log.d(TAG, "저장 완료: id=${recording.id}, rating=$rating, ${recording.displayName}")

                // 중요(★)면 프리미엄 백업 시도 (내부에서 프리미엄/폴더 자격 검사)
                if (rating == Rating.KEEP) {
                    val r = BackupManager.backup(app, recording)
                    Log.d(TAG, r.msg)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "저장 실패", e)
                false
            }
        }
    }
}
