package com.blackcore.callstar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blackcore.callstar.data.AppDatabase
import com.blackcore.callstar.data.Rating
import com.blackcore.callstar.data.RatingEntity
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * [디버그 전용] 자동 정리 시험 장치. 실제 △정리후보는 건드리지 않고 이름에 TESTTRASH 가 든 파일만 대상.
 *  --es mode next     : 예약 시각 계산 결과 로그
 *  --es mode silent   : TESTTRASH 파일을 △로 표시 → 확인창 없이 휴지통(권한 필요) → 결과 알림
 *  --es mode pending  : TESTTRASH 파일을 △로 표시 → "정리 준비" 알림(권한 없을 때 경로)
 */
class DebugAutoCleanProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val T = "CallStar/ACProbe"
        val app = context.applicationContext
        when (intent.getStringExtra("mode")) {
            "next" -> {
                val f = SimpleDateFormat("yyyy-MM-dd(E) HH:mm", Locale.KOREA)
                val now = System.currentTimeMillis()
                Log.d(T, "now=" + f.format(Date(now)))
                for ((dow, hour, weeks, lastDaysAgo) in listOf(
                    listOf(Calendar.SUNDAY, 3, 1, -1), listOf(Calendar.MONDAY, 2, 1, -1),
                    listOf(Calendar.SUNDAY, 3, 1, 0), listOf(Calendar.SUNDAY, 3, 2, 0), listOf(Calendar.SUNDAY, 3, 4, 0),
                    listOf(Calendar.SUNDAY, 3, 2, 10),
                )) {
                    val last = if (lastDaysAgo < 0) 0L else now - lastDaysAgo * 86_400_000L
                    val n = AutoClean.computeNext(now, dow, hour, weeks, last)
                    Log.d(T, "dow=$dow hour=$hour weeks=$weeks lastDaysAgo=$lastDaysAgo -> " + f.format(Date(n)))
                }
            }
            "restore" -> {
                // TESTTRASH 휴지통 파일 되살리기(권한 있을 때 확인창 없음)
                val args = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%TESTTRASH%"))
                    putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED, android.provider.MediaStore.MATCH_ONLY)
                }
                val uris = ArrayList<android.net.Uri>()
                app.contentResolver.query(AutoClean.COLLECTION, arrayOf("_id"), args, null)?.use { c ->
                    while (c.moveToNext()) uris += android.content.ContentUris.withAppendedId(AutoClean.COLLECTION, c.getLong(0))
                }
                Log.d(T, "restore n=${uris.size}")
                if (uris.isNotEmpty()) {
                    val opts = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
                    android.provider.MediaStore.createTrashRequest(app.contentResolver, uris, false).send(app, 0, null, null, null, null, opts)
                }
            }
            "silent", "pending" -> {
                val pending = goAsync()
                Thread {
                    try {
                        val list = CallRecordingFinder.queryCallRecordings(app)
                            .filter { it.displayName.contains("TESTTRASH") }
                            .map { AutoClean.Target(it.id, it.displayName, it.sizeBytes) }
                        runBlocking {
                            val dao = AppDatabase.get(app).ratingDao()
                            list.forEach { dao.upsert(RatingEntity(it.id, Rating.LATER, it.name, System.currentTimeMillis())) }
                        }
                        Log.d(T, "test targets=${list.size} canManage=${AutoClean.canManageMedia(app)}")
                        if (intent.getStringExtra("mode") == "silent") AutoClean.trashSilently(app, list)
                        else {
                            val m = AutoClean::class.java.getDeclaredMethod("showPending", Context::class.java, List::class.java)
                            m.isAccessible = true; m.invoke(AutoClean, app, list)
                        }
                        Log.d(T, "done")
                    } finally { pending.finish() }
                }.start()
            }
        }
    }
}
