package com.blackcore.callstar

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blackcore.callstar.data.AppDatabase
import com.blackcore.callstar.data.AppPrefs
import com.blackcore.callstar.data.Rating
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * [플러스] 자동 정리.
 *
 * 사용자가 고른 요일·새벽 시간에(주기 1/2/4주) △정리후보로 표시된 통화녹음을 전부
 * **안드로이드 휴지통**으로 보낸다. 휴지통 파일은 30일 뒤 시스템이 완전 삭제하고,
 * 그 전에는 앱의 "정리된 통화" 화면에서 되살릴 수 있다.
 *
 * - 미디어 관리 권한(MANAGE_MEDIA, 안드12+)이 있으면 확인창 없이 새벽에 조용히 처리.
 *   (실기 확인: 백그라운드에서 보낸 createTrashRequest 가 BAL 허용 목록으로 통과, 확인창 없음)
 * - 권한이 없으면 "정리 준비됨" 알림을 띄우고, 사용자가 탭하면 시스템 확인창으로 처리.
 * - 예약은 정확한 시각 알람(제한 권한) 대신 setAndAllowWhileIdle — 설정 시각 전후 수십 분 안에 실행.
 */
object AutoClean {

    private const val TAG = "CallStar/AutoClean"
    private const val CHANNEL = "auto_clean"
    private const val NOTIF_RESULT = 1003
    private const val NOTIF_PENDING = 1004
    const val ACTION_RUN = "com.blackcore.callstar.AUTO_CLEAN"

    val COLLECTION: Uri get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    // ── 예약 ──────────────────────────────────────────────

    /** 설정을 바탕으로 다음 실행 시각(epoch millis). 꺼져 있으면 null. */
    fun nextRunAt(context: Context, now: Long = System.currentTimeMillis()): Long? {
        if (!AppPrefs.isPremium(context) || !AppPrefs.autoCleanEnabled(context)) return null
        return computeNext(now, AppPrefs.autoCleanDow(context), AppPrefs.autoCleanHour(context),
            AppPrefs.autoCleanWeeks(context), AppPrefs.autoCleanLastRun(context))
    }

    /** 순수 계산: 다음 실행 시각. */
    fun computeNext(now: Long, dow: Int, hour: Int, weeksIn: Int, last: Long): Long {
        val weeks = weeksIn.coerceIn(1, 4)
        // 마지막 실행 후 주기가 지나야 다음 실행(하루 여유: 실행이 몇 시간 늦어져도 다음 주기를 건너뛰지 않게)
        val earliest = maxOf(now, if (last > 0) last + weeks * 7L * 86_400_000L - 24 * 3_600_000L else now)
        val cal = Calendar.getInstance().apply {
            timeInMillis = earliest
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.get(Calendar.DAY_OF_WEEK) != dow || cal.timeInMillis <= earliest) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 7001,
            Intent(context, AutoCleanReceiver::class.java).setAction(ACTION_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 설정 변경·앱 시작·재부팅·실행 직후에 호출. 꺼져 있으면 예약 취소. */
    fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        val at = nextRunAt(context)
        am.cancel(pi)
        if (at != null) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Log.d(TAG, "예약: $at")
        } else {
            Log.d(TAG, "예약 취소")
        }
    }

    // ── 실행 ──────────────────────────────────────────────

    data class Target(val id: Long, val name: String, val size: Long)

    /** 지금 휴지통으로 보낼 대상: △정리후보이고 아직 실제로 있는 통화녹음. */
    fun targets(context: Context): List<Target> {
        val later = runBlocking { AppDatabase.get(context).ratingDao().getAllOnce() }
            .filter { it.rating == Rating.LATER }.map { it.mediastoreId }.toHashSet()
        if (later.isEmpty()) return emptyList()
        return CallRecordingFinder.queryCallRecordings(context)
            .filter { it.id in later }
            .map { Target(it.id, it.displayName, it.sizeBytes) }
    }

    /** 예약 시각에 리시버에서 호출(백그라운드 스레드). */
    fun runScheduled(context: Context) {
        AppPrefs.setAutoCleanLastRun(context, System.currentTimeMillis())
        try {
            if (!AppPrefs.isPremium(context) || !AppPrefs.autoCleanEnabled(context)) return
            val list = targets(context)
            Log.d(TAG, "대상 ${list.size}건")
            if (list.isEmpty()) return
            if (canManageMedia(context)) {
                trashSilently(context, list)
            } else {
                showPending(context, list)
            }
        } finally {
            reschedule(context)
        }
    }

    /** 미디어 관리 권한이 있을 때: 확인창 없이 휴지통으로. 실제로 옮겨진 것만 세어 결과 알림. */
    fun trashSilently(context: Context, list: List<Target>) {
        val uris = list.map { ContentUris.withAppendedId(COLLECTION, it.id) }
        try {
            val pi = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pi.send(context, 0, null, null, null, null, opts)
        } catch (e: Exception) {
            Log.e(TAG, "휴지통 요청 실패", e)
            showPending(context, list)   // 실패하면 사용자가 직접 처리하도록
            return
        }
        // 시스템이 처리할 시간을 잠깐 준 뒤 실제 결과 확인
        var done: List<Target> = emptyList()
        repeat(10) {
            Thread.sleep(800)
            done = list.filter { isTrashed(context, it.id) }
            if (done.size == list.size) return@repeat
        }
        onTrashed(context, done)
    }

    fun isTrashed(context: Context, id: Long): Boolean {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media._ID}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return try {
            context.contentResolver.query(COLLECTION, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)
                ?.use { c -> c.moveToFirst() && c.getInt(0) == 1 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** 휴지통 이동 완료 후(조용히 처리했든, 사용자가 확인했든) 공통 마무리. */
    fun onTrashed(context: Context, done: List<Target>) {
        NotificationManagerCompat.from(context).cancel(NOTIF_PENDING)
        if (done.isEmpty()) return
        runBlocking { AppDatabase.get(context).ratingDao().deleteByIds(done.map { it.id }) }
        showResult(context, done.size, done.sumOf { it.size })
    }

    // ── 알림 ──────────────────────────────────────────────

    private fun ensureChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "자동 정리", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "정리후보 통화를 휴지통으로 옮긴 결과 알림"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun openApp(context: Context, req: Int, trashView: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context, req,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TRASH, trashView),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun periodLabel(context: Context): String = when (AppPrefs.autoCleanWeeks(context)) {
        2 -> "지난 2주"
        4 -> "지난 4주"
        else -> "이번 주"
    }

    private fun showResult(context: Context, count: Int, bytes: Long) {
        if (!NotificationHelper.canPost(context)) return
        ensureChannel(context)
        val title = "${periodLabel(context)} 통화서랍 ${count}건 정리 · ${fmtBytes(bytes)} 확보"
        val text = "휴지통에 30일 보관 후 완전 삭제돼요 · 탭해서 되살리기"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rating)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, 7101, trashView = true))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIF_RESULT, n) } catch (_: SecurityException) {}
    }

    /** 권한이 없어 조용히 못 옮길 때: 탭하면 시스템 확인창으로 휴지통 이동. */
    private fun showPending(context: Context, list: List<Target>) {
        if (!NotificationHelper.canPost(context)) return
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context, 7102,
            DeleteRequestActivity.trashIntent(context, list.map { it.id }.toLongArray()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "탭하면 휴지통으로 옮겨요(30일 뒤 완전 삭제) · 설정에서 미디어 관리 권한을 켜면 자동으로 처리돼요"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rating)
            .setContentTitle("정리후보 ${list.size}건 · ${fmtBytes(list.sumOf { it.size })} 정리 준비")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIF_PENDING, n) } catch (_: SecurityException) {}
    }

    fun fmtBytes(b: Long): String {
        if (b <= 0) return "0MB"
        val mb = b / 1024.0 / 1024.0
        return if (mb >= 1024) "%.1fGB".format(mb / 1024) else if (mb >= 1) "%.0fMB".format(mb) else "%.0fKB".format(b / 1024.0)
    }
}

/** 예약 알람 수신 → 자동 정리 실행. */
class AutoCleanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoClean.ACTION_RUN) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { AutoClean.runScheduled(app) } catch (e: Exception) {
                Log.e("CallStar/AutoClean", "실행 오류", e)
            } finally { pending.finish() }
        }.start()
    }
}

/** 재부팅·앱 업데이트 후 예약 복구(알람은 재부팅 시 사라짐). */
class AutoCleanBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                AutoClean.reschedule(context.applicationContext)
        }
    }
}
