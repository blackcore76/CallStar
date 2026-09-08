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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 통화 종료 후 "중요도 남기기" 알림.
 *
 * 오버레이 팝업과 달리 잠금화면/알림창에 남아있어, 플립을 닫거나 잊어버려도
 * 다음에 폰을 볼 때 알림 버튼으로 바로 마킹할 수 있다(실사용 행동 패턴에 맞춤).
 *
 * ★ 알림 지옥 방지: 통화마다 새 알림을 쌓지 않는다.
 *   - 알림은 항상 딱 1개(NOTIF_ID 고정).
 *   - 안 찍은 통화는 대기열(SharedPreferences)에 누적되고, 알림엔 "외 N건 더" 로 표시.
 *   - 버튼으로 하나 처리하면 대기열에서 빠지고 다음 통화로 알림이 교체된다.
 *   - 대기열이 비면 알림이 사라진다.
 */
object NotificationHelper {

    private const val CHANNEL_ID = "call_rating"
    private const val CHANNEL_AUTO_ID = "auto_mark"
    private const val TAG = "CallStar/Noti"

    // 고정 알림 ID — 언제나 이 한 개만 존재한다.
    private const val NOTIF_ID = 1001

    // 자동 중요 마킹 안내(정보성)도 고정 ID로 최신 1건만 표시(스택 방지).
    private const val NOTIF_ID_AUTO = 1002

    private const val QUEUE_PREFS = "callstar_noti_queue"
    private const val KEY_QUEUE = "pending"

    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "통화 후 중요도",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "통화가 끝나면 중요도를 남길 수 있는 알림"
                setShowBadge(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    // ── 대기열 저장/조회 ─────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)

    private data class Pending(val id: Long, val name: String)

    private fun loadQueue(context: Context): MutableList<Pending> {
        val raw = prefs(context).getString(KEY_QUEUE, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Pending(o.getLong("id"), o.optString("n"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveQueue(context: Context, list: List<Pending>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("n", p.name))
        }
        prefs(context).edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** 새 통화를 대기열에 추가하고 알림을 갱신한다. */
    fun showRatingNotification(context: Context, recording: CallRecording) {
        enqueue(context, recording)
    }

    fun enqueue(context: Context, recording: CallRecording) {
        if (!canPost(context)) return
        val q = loadQueue(context)
        // 같은 통화가 이미 대기 중이면 중복 추가 안 함(맨 뒤로 올리지도 않음).
        if (q.none { it.id == recording.id }) {
            q.add(Pending(recording.id, recording.displayName))
            saveQueue(context, q)
        }
        // 새 통화이므로 소리/헤드업으로 알린다.
        render(context, alert = true)
    }

    /** 한 통화를 처리 완료로 보고 대기열에서 제거 후 다음 통화로 알림 교체(비면 알림 제거). */
    fun advance(context: Context, ratedId: Long) {
        val q = loadQueue(context)
        val changed = q.removeAll { it.id == ratedId }
        if (changed) saveQueue(context, q)
        // 이어지는 통화는 조용히 카드만 교체(연속 처리 시 알림 폭탄 방지).
        render(context, alert = false)
    }

    private fun ensureAutoChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_AUTO_ID,
                "자동 중요 표시 안내",
                NotificationManager.IMPORTANCE_LOW,   // 조용히(소리/헤드업 없음)
            ).apply {
                description = "등록한 번호/이름과의 통화를 자동으로 중요 표시했을 때 알림"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    /** 자동 중요 마킹됨을 조용히 안내(정보성, 탭하면 목록에서 재분류 가능). */
    fun showAutoMarked(context: Context, recording: CallRecording, keyword: String) {
        if (!canPost(context)) return
        ensureAutoChannel(context)
        val name = recording.displayName.removePrefix("통화 녹음 ").removePrefix("통화 ")

        val contentPI = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val backupNote = if (AppPrefsBackupReady(context)) " · 백업함" else ""
        val notif = NotificationCompat.Builder(context, CHANNEL_AUTO_ID)
            .setSmallIcon(R.drawable.ic_stat_rating)
            .setContentTitle("★ 자동 중요 표시 · $name")
            .setContentText("등록한 ‘$keyword’ 와의 통화라 중요로 표시했어요$backupNote · 탭하면 바꿀 수 있어요")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "등록한 ‘$keyword’ 와의 통화라 중요로 표시했어요$backupNote.\n바꾸려면 탭해서 목록에서 재분류하세요.",
                ),
            )
            .setContentIntent(contentPI)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_AUTO, notif)
        } catch (e: SecurityException) {
        }
    }

    private fun AppPrefsBackupReady(context: Context): Boolean =
        com.blackcore.callstar.data.AppPrefs.isPremium(context) &&
            com.blackcore.callstar.data.AppPrefs.backupTreeUri(context) != null

    /** 현재 중요도 미처리로 대기 중인 통화 id 목록(알림 대기열). */
    fun pendingIds(context: Context): List<Long> = loadQueue(context).map { it.id }

    /**
     * 대기열을 실제 "미평가 통화" 집합과 대조해 정리한다.
     * 앱 안에서 마킹/삭제한 건은 알림 버튼을 안 거쳤어도 여기서 빠진다.
     * (앱이 목록을 새로 불러올 때마다 호출 → 알림 카운트/표시가 항상 최신)
     */
    fun reconcile(context: Context, stillUnratedIds: Set<Long>) {
        val q = loadQueue(context)
        val kept = q.filter { it.id in stillUnratedIds }
        if (kept.size != q.size) {
            saveQueue(context, kept)
            render(context, alert = false)
        }
    }

    /** 대기열 전체 비우고 알림 제거(설정에서 알림 방식 끌 때 등). */
    fun clearAll(context: Context) {
        saveQueue(context, emptyList())
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    // ── 렌더링 ──────────────────────────────────────────────────

    private fun render(context: Context, alert: Boolean) {
        val q = loadQueue(context)
        if (q.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
            return
        }
        if (!canPost(context)) return
        ensureChannel(context)

        // 가장 최근(맨 뒤) 통화를 먼저 보여준다 — 방금 통화라 기억이 선명하다.
        val head = q.last()
        val remaining = q.size - 1
        val name = head.name.removePrefix("통화 녹음 ").removePrefix("통화 ")

        fun actionIntent(action: String, reqOffset: Int): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(NotificationActionReceiver.EXTRA_ID, head.id)
                putExtra(NotificationActionReceiver.EXTRA_NAME, head.name)
            }
            return PendingIntent.getBroadcast(
                context, reqOffset, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val contentPI = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(MainActivity.EXTRA_FROM_NOTI, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (remaining > 0) "방금 통화 · $name  (외 ${remaining}건)" else "방금 통화 · $name"
        val text = if (remaining > 0) {
            "중요도를 남겨주세요 · 처리하면 남은 ${remaining}건이 이어서 떠요"
        } else {
            "중요도를 남겨주세요"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rating)
            .setContentTitle(title)
            .setContentText(text)
            .addAction(0, "★ 중요", actionIntent(NotificationActionReceiver.ACTION_KEEP, 1))
            .addAction(0, "△ 정리후보", actionIntent(NotificationActionReceiver.ACTION_LATER, 2))
            .addAction(0, "🗑 삭제", actionIntent(NotificationActionReceiver.ACTION_DELETE, 3))
            .setContentIntent(contentPI)
            .setAutoCancel(false)
            // alert=false 면 기존 알림을 조용히 갱신(소리/헤드업 재발생 없음).
            .setOnlyAlertOnce(!alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        // 대기 중인 통화가 여러 건이면 뱃지 숫자로도 보여준다.
        if (q.size > 1) builder.setNumber(q.size)

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            // 권한 없음 등
        }
    }
}
