package com.blackcore.callstar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [디버그 전용] 오늘 통화 중 최근 n건을 알림 대기열에 넣어 통화 직후 알림을 재현한다.
 * adb shell am broadcast -n com.blackcore.callstar/.DebugNotifyReceiver --ei n 3
 */
class DebugNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val n = intent.getIntExtra("n", 3)
        val recent = CallRecordingFinder.queryCallRecordings(context, limit = n)
        recent.reversed().forEach { NotificationHelper.enqueue(context, it) }   // 가장 최근이 맨 앞에 보이도록
    }
}
