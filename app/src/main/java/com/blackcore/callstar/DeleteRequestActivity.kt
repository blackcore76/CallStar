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

        fun intent(context: android.content.Context, ids: LongArray): Intent =
            Intent(context, DeleteRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_IDS, ids)
            }
    }

    private lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>
    private var targetIds: List<Long> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetIds = intent.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
            .filter { it > 0L }

        launcher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
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
        requestDelete(targetIds)
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
