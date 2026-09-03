package com.blackcore.callstar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 통화 종료 후 "별점 남기기" 알림.
 *
 * 오버레이 팝업과 달리 잠금화면/알림창에 남아있어, 플립을 닫거나 잊어버려도
 * 다음에 폰을 볼 때 알림 버튼으로 바로 마킹할 수 있다(실사용 행동 패턴에 맞춤).
 */
object NotificationHelper {

    private const val CHANNEL_ID = "call_rating"
    private const val TAG = "CallStar/Noti"

    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "통화 후 별점",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "통화가 끝나면 중요도를 남길 수 있는 알림"
                setShowBadge(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    fun showRatingNotification(context: Context, recording: CallRecording) {
        if (!canPost(context)) return
        ensureChannel(context)

        val notifId = (recording.id and 0x7FFFFFFF).toInt()
        val name = recording.displayName.removePrefix("통화 녹음 ").removePrefix("통화 ")

        fun actionIntent(action: String, reqOffset: Int): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(NotificationActionReceiver.EXTRA_ID, recording.id)
                putExtra(NotificationActionReceiver.EXTRA_NAME, recording.displayName)
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                context, notifId * 10 + reqOffset, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val contentPI = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rating)
            .setContentTitle("방금 통화 · $name")
            .setContentText("중요도를 남겨주세요")
            .addAction(0, "★ 중요", actionIntent(NotificationActionReceiver.ACTION_KEEP, 1))
            .addAction(0, "△ 정리후보", actionIntent(NotificationActionReceiver.ACTION_LATER, 2))
            .addAction(0, "🗑 삭제", actionIntent(NotificationActionReceiver.ACTION_DELETE, 3))
            .setContentIntent(contentPI)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifId, notif)
        } catch (e: SecurityException) {
            // 권한 없음 등
        }
    }

    fun cancel(context: Context, notifId: Int) {
        NotificationManagerCompat.from(context).cancel(notifId)
    }
}
