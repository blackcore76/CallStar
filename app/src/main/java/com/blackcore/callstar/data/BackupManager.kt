package com.blackcore.callstar.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.blackcore.callstar.CallRecording

/**
 * [프리미엄] "중요(★)" 로 표시한 통화녹음을 사용자가 지정한 폴더로 복사(백업).
 * - 원본은 그대로 둔다(복사). 삼성 보존정책/수동삭제로부터 중요 통화 보호.
 * - SAF(트리 URI, 영구 권한)로 추가 저장권한 없이 기록. 오버레이(백그라운드)에서 조용히 동작.
 */
object BackupManager {

    private const val TAG = "CallStar/Backup"

    sealed class Result(val msg: String) {
        object NotPremium : Result("프리미엄 아님 — 백업 생략")
        object NoFolder : Result("백업 폴더 미지정")
        object AlreadyExists : Result("이미 백업됨")
        data class Ok(val name: String) : Result("백업 완료: $name")
        data class Failed(val reason: String) : Result("백업 실패: $reason")
    }

    fun backup(context: Context, recording: CallRecording): Result {
        val app = context.applicationContext
        if (!AppPrefs.isPremium(app)) return Result.NotPremium
        val treeStr = AppPrefs.backupTreeUri(app) ?: return Result.NoFolder

        return try {
            val tree = DocumentFile.fromTreeUri(app, Uri.parse(treeStr))
                ?: return Result.Failed("폴더 열기 실패")
            val name = recording.displayName
            tree.findFile(name)?.let { return Result.AlreadyExists }

            val dest = tree.createFile("audio/mp4", name)
                ?: return Result.Failed("대상 파일 생성 실패")

            app.contentResolver.openInputStream(recording.uri).use { input ->
                if (input == null) return Result.Failed("원본 열기 실패")
                app.contentResolver.openOutputStream(dest.uri).use { output ->
                    if (output == null) return Result.Failed("대상 쓰기 실패")
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "백업 완료: $name")
            Result.Ok(name)
        } catch (e: Exception) {
            Log.e(TAG, "백업 예외", e)
            Result.Failed(e.message ?: "예외")
        }
    }
}
