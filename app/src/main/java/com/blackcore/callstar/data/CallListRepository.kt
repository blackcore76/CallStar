package com.blackcore.callstar.data

import android.content.Context
import com.blackcore.callstar.CallRecording
import com.blackcore.callstar.CallRecordingFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 목록 한 줄: 통화녹음 + (있으면) 별점 */
data class CallRow(
    val recording: CallRecording,
    val rating: Int?,   // null = 미평가
)

object CallListRepository {

    /**
     * 통화녹음 목록 + 별점 결합.
     * 로드 시 DB의 별점 중 현재 MediaStore 에 없는 _ID(사라진 파일)는 정리한다(재매칭).
     */
    suspend fun load(context: Context): List<CallRow> = withContext(Dispatchers.IO) {
        val recs = CallRecordingFinder.queryCallRecordings(context)   // 전체, 최신순
        val dao = AppDatabase.get(context).ratingDao()
        val ratings = dao.getAllOnce().associate { it.mediastoreId to it.rating }

        // 재매칭: 현재 파일 목록에 없는 별점 행 제거
        val presentIds = recs.map { it.id }.toHashSet()
        val stale = ratings.keys.filter { it !in presentIds }
        if (stale.isNotEmpty()) dao.deleteByIds(stale)

        recs.map { CallRow(it, ratings[it.id]) }
    }

    /** 삭제 확정 후 DB 별점도 정리 (MediaStore 에서 사라지면 다음 load 에서도 정리되지만 즉시 반영) */
    suspend fun forgetRatings(context: Context, ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isNotEmpty()) AppDatabase.get(context).ratingDao().deleteByIds(ids)
    }
}
