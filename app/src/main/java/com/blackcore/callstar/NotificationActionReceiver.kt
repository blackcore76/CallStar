package com.blackcore.callstar

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.blackcore.callstar.data.Rating
import com.blackcore.callstar.data.RatingStore

/**
 * 별점 알림의 버튼 처리: ★중요 / △정리후보 / 🗑삭제.
 * 앱을 열지 않고 알림에서 바로 마킹(중요는 백업까지). 삭제는 시스템 확인창을 띄운다.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStar/NotiAction"
        const val ACTION_KEEP = "com.blackcore.callstar.KEEP"
        const val ACTION_LATER = "com.blackcore.callstar.LATER"
        const val ACTION_DELETE = "com.blackcore.callstar.DELETE"
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_NOTIF_ID = "notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (id <= 0L) return

        val app = context.applicationContext
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri: Uri = ContentUris.withAppendedId(collection, id)
        val rec = CallRecording(id, name, "", 0, 0, 0, 0, uri)

        when (intent.action) {
            ACTION_KEEP -> {
                saveAsync(app, rec, Rating.KEEP)
                if (notifId >= 0) NotificationHelper.cancel(app, notifId)
            }
            ACTION_LATER -> {
                saveAsync(app, rec, Rating.LATER)
                if (notifId >= 0) NotificationHelper.cancel(app, notifId)
            }
            ACTION_DELETE -> {
                // 시스템 삭제 확인창(투명 액티비티 경유)
                try {
                    app.startActivity(DeleteRequestActivity.intent(app, longArrayOf(id)))
                } catch (e: Exception) {
                    Log.e(TAG, "삭제 액티비티 시작 실패", e)
                }
                if (notifId >= 0) NotificationHelper.cancel(app, notifId)
            }
        }
    }

    /** goAsync 로 DB 저장(+백업) 시간을 확보 */
    private fun saveAsync(context: Context, rec: CallRecording, rating: Int) {
        val pending = goAsync()
        Thread {
            try {
                kotlinx.coroutines.runBlocking { RatingStore.saveNow(context, rec, rating) }
                Log.d(TAG, "알림 마킹: id=${rec.id}, rating=$rating")
            } catch (e: Exception) {
                Log.e(TAG, "마킹 실패", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
