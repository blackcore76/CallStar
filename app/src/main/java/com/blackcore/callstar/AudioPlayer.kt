package com.blackcore.callstar

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * 분류 팝업에서 통화녹음 미리듣기용 단일 플레이어.
 * content:// URI 재생 + 진행위치/길이/탐색(seek) 제공.
 */
class AudioPlayer {
    private var mp: MediaPlayer? = null
    private var prepared = false

    fun play(context: Context, uri: Uri, onComplete: () -> Unit) {
        stop()
        prepared = false
        try {
            mp = MediaPlayer().apply {
                setDataSource(context.applicationContext, uri)
                setOnPreparedListener { prepared = true; start() }
                setOnCompletionListener { onComplete(); stop() }
                setOnErrorListener { _, _, _ -> onComplete(); stop(); true }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("CallStar/Player", "재생 실패", e)
            onComplete()
        }
    }

    fun stop() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        prepared = false
    }

    fun isPlaying(): Boolean = try { mp?.isPlaying == true } catch (_: Exception) { false }

    /** 현재 위치(ms). 준비 전이면 0. */
    fun positionMs(): Int = if (prepared) try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 } else 0

    /** 전체 길이(ms). 준비 전이면 0. */
    fun durationMs(): Int = if (prepared) try { mp?.duration ?: 0 } catch (_: Exception) { 0 } else 0

    fun seekTo(ms: Int) { if (prepared) try { mp?.seekTo(ms) } catch (_: Exception) {} }
}
