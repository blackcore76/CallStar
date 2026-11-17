package com.blackcore.callstar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * 새 버전 안내. 폰 안의 Play 스토어 앱에 "받을 수 있는 새 버전이 있나" 물어보고(네트워크 권한 없음),
 * 있으면 메인 화면 위에 배너 → 누르면 Play 스토어의 통화서랍 페이지로 이동.
 * Play에서 받지 않은 설치(디버그·직접 설치)는 실패하므로 조용히 넘어간다.
 */
object AppUpdate {
    /** 새 버전 코드(없으면 0)를 메인 스레드 콜백으로 돌려준다. */
    fun check(ctx: Context, done: (Int) -> Unit) {
        try {
            AppUpdateManagerFactory.create(ctx.applicationContext).appUpdateInfo
                .addOnSuccessListener { info ->
                    val avail = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    done(if (avail && info.availableVersionCode() > BuildConfig.VERSION_CODE) info.availableVersionCode() else 0)
                }
                .addOnFailureListener { done(0) }
        } catch (_: Exception) {
            done(0)
        }
    }

    fun openStore(ctx: Context) {
        val id = ctx.packageName
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                    .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }
}
