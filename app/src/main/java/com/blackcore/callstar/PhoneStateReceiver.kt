package com.blackcore.callstar

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * [단계 1] 통화 종료(IDLE 전환) 감지 → 방금 생성된 통화녹음 파일명을 Toast.
 *
 * 설계 메모:
 *  - 상시 포그라운드 서비스 없음. 매니페스트 등록 리시버만 사용.
 *  - PHONE_STATE 는 백그라운드 브로드캐스트 제한 예외라 앱 미실행 상태에서도 전달됨.
 *  - onReceive 는 반환 후 프로세스가 곧 죽을 수 있어, goAsync() 로 ~10초 창을 확보하고
 *    그 안에서 지연 폴링(삼성은 IDLE 시점에 녹음 파일 flush 가 아직 안 끝남).
 *  - 상태 전이 추적(OFFHOOK→IDLE)은 리시버 인스턴스가 매번 새로 생성되므로
 *    SharedPreferences 에 저장.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStar/Receiver"
        private const val PREFS = "callstar_phone_state"
        private const val KEY_IN_CALL = "was_in_call"

        // 이 시간(ms) 안에 수정된 파일만 "방금 통화" 로 인정 (직전 통화 오인 방지)
        private const val FRESH_WINDOW_MS = 2 * 60 * 1000L

        // goAsync 창 안에서의 폴링 스케줄
        private val POLL_DELAYS_MS = longArrayOf(1500L, 3000L, 5000L, 8000L)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        Log.d(TAG, "PHONE_STATE = $state")

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // 통화 연결(발신/수신 응답). 통화 중 플래그 세움.
                prefs.edit().putBoolean(KEY_IN_CALL, true).apply()
            }

            TelephonyManager.EXTRA_STATE_RINGING -> {
                // 벨울림만으로는 아무 것도 안 함. (부재중은 녹음 없음)
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val wasInCall = prefs.getBoolean(KEY_IN_CALL, false)
                if (!wasInCall) {
                    // 실제 통화 없이 들어온 IDLE (부재중/거절 등). 무시.
                    Log.d(TAG, "IDLE 이지만 통화 없었음 → 무시")
                    return
                }
                prefs.edit().putBoolean(KEY_IN_CALL, false).apply()
                handleCallEnded(context)
            }
        }
    }

    private fun handleCallEnded(context: Context) {
        if (!hasAudioReadPermission(context)) {
            Log.w(TAG, "오디오 읽기 권한 없음 → 조회 불가")
            showToast(context, "CallStar: 녹음 접근 권한이 없어요")
            return
        }

        val callEndAt = System.currentTimeMillis()
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val appContext = context.applicationContext

        // 폴링: 신선한 녹음 파일이 잡히면 Toast 하고 종료. 못 잡으면 마지막에 안내.
        val found = booleanArrayOf(false)

        POLL_DELAYS_MS.forEachIndexed { idx, delay ->
            handler.postDelayed({
                if (found[0]) return@postDelayed

                val rec = CallRecordingFinder.findLatestCallRecording(appContext)
                val fresh = rec != null &&
                    (callEndAt - rec.dateModifiedSec * 1000L) <= FRESH_WINDOW_MS

                Log.d(
                    TAG,
                    "poll#$idx(+${delay}ms): " +
                        if (rec == null) "파일 없음"
                        else "${rec.displayName} (mod=${rec.dateModifiedSec}, fresh=$fresh)"
                )

                if (fresh) {
                    found[0] = true

                    // ★ 자동 중요 마킹(프리미엄): 등록된 이름/번호와 파일명이 매칭되면
                    //   묻지 않고 바로 중요로 표시(+백업). 별점 요청 알림/팝업은 생략.
                    val autoKw = AutoMarkRules.autoKeepMatch(appContext, rec!!.displayName)
                    if (autoKw != null) {
                        try {
                            kotlinx.coroutines.runBlocking {
                                com.blackcore.callstar.data.RatingStore.saveNow(
                                    appContext, rec, com.blackcore.callstar.data.Rating.KEEP,
                                )
                            }
                            Log.d(TAG, "자동 중요 마킹: '${autoKw}' 매칭 → ${rec.displayName}")
                            if (NotificationHelper.canPost(appContext)) {
                                NotificationHelper.showAutoMarked(appContext, rec, autoKw)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "자동 중요 마킹 실패", e)
                        }
                        pending.finish()
                        return@postDelayed
                    }

                    // 기본: 알림(잠금화면/알림창에 남아 나중에 마킹 가능).
                    // 설정에서 오버레이 팝업을 켠 경우에만 즉시 팝업.
                    val useOverlay = com.blackcore.callstar.data.AppPrefs.postCallOverlay(appContext)
                    if (useOverlay && RatingOverlay.canDraw(appContext)) {
                        RatingOverlay.show(appContext, rec!!)
                    } else if (NotificationHelper.canPost(appContext)) {
                        NotificationHelper.showRatingNotification(appContext, rec!!)
                    } else if (RatingOverlay.canDraw(appContext)) {
                        // 알림 권한 없으면 오버레이로 폴백
                        RatingOverlay.show(appContext, rec!!)
                    } else {
                        Log.w(TAG, "알림/오버레이 권한 모두 없음 → 표시 생략: ${rec!!.displayName}")
                    }
                    pending.finish()
                } else if (idx == POLL_DELAYS_MS.lastIndex) {
                    // 마지막 폴까지 신선 파일 못 찾음
                    Log.w(
                        TAG,
                        if (rec == null) "통화녹음 파일 못 찾음(녹음 꺼짐/경로 다름?)"
                        else "최근 파일이 방금 통화 아님: ${rec.displayName}",
                    )
                    pending.finish()
                }
            }, delay)
        }
    }

    private fun hasAudioReadPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
