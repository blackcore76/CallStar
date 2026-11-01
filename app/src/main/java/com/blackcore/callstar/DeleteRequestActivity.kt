package com.blackcore.callstar

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.blackcore.callstar.data.CallListRepository
import kotlinx.coroutines.launch

/**
 * 통화 종료 팝업의 "🗑 삭제" 버튼 전용 얇은 투명 액티비티.
 *
 * 왜 필요한가: 오버레이(WindowManager)는 백그라운드 컨텍스트라 시스템 삭제 확인
 * 다이얼로그(IntentSender)를 직접 못 띄운다. 이 액티비티가 잠깐 떠서
 * MediaStore.createDeleteRequest 의 확인창을 대신 실행하고, 끝나면 스스로 닫힌다.
 * (투명 테마라 화면엔 시스템 삭제창만 보인다.)
 */
class DeleteRequestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CallStar/Delete"
        const val EXTRA_IDS = "ids"
        const val EXTRA_TRASH = "trash"   // true면 완전 삭제 대신 휴지통(30일 보관)

        /** 자동 정리 "정리 준비" 알림 탭 → 휴지통 이동(권한 없을 때 시스템 확인창). */
        fun trashIntent(context: android.content.Context, ids: LongArray): Intent =
            intent(context, ids).putExtra(EXTRA_TRASH, true)

        fun intent(context: android.content.Context, ids: LongArray): Intent =
            Intent(context, DeleteRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_IDS, ids)
            }
    }

    private lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>
    private var targetIds: List<Long> = emptyList()
    private var trashMode = false
    private var trashTargets: List<AutoClean.Target> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trashMode = intent.getBooleanExtra(EXTRA_TRASH, false)
        targetIds = intent.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
            .filter { it > 0L }

        launcher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && trashMode) {
                Log.d(TAG, "휴지통 이동 확정: $targetIds")
                val done = trashTargets
                Thread { AutoClean.onTrashed(applicationContext, done) }.start()
                finish()
            } else if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "삭제 확정: $targetIds")
                lifecycleScope.launch {
                    CallListRepository.forgetRatings(applicationContext, targetIds)
                    finish()
                }
            } else {
                Log.d(TAG, "삭제 취소")
                finish()
            }
        }

        if (targetIds.isEmpty()) {
            finish()
            return
        }
        if (trashMode) requestTrash(targetIds) else requestDelete(targetIds)
    }

    private fun requestTrash(ids: List<Long>) {
        // 결과 알림용 이름·크기 (지금 실제 있는 것만)
        trashTargets = CallRecordingFinder.queryCallRecordings(this)
            .filter { it.id in ids }.map { AutoClean.Target(it.id, it.displayName, it.sizeBytes) }
        val uris = trashTargets.map { ContentUris.withAppendedId(AutoClean.COLLECTION, it.id) }
        if (uris.isEmpty()) { finish(); return }
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, uris, true)
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            Log.e(TAG, "휴지통 요청 실패", e)
            finish()
        }
    }

    private fun requestDelete(ids: List<Long>) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uris: List<Uri> = ids.map { ContentUris.withAppendedId(collection, it) }
        try {
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            Log.e(TAG, "삭제 요청 실패", e)
            finish()
        }
    }
}
