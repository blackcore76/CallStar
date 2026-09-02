package com.blackcore.callstar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.blackcore.callstar.data.AppPrefs
import com.blackcore.callstar.data.Rating
import com.blackcore.callstar.data.RatingStore

/**
 * [단계 2] 통화 종료 시 화면 위에 뜨는 별점 팝업.
 *
 * 왜 Activity 가 아니라 WindowManager.addView 인가:
 *  - 백그라운드(얼려진) 상태의 리시버에서 Activity 를 띄우는 건 Android 10+ 에서 막힘.
 *  - SYSTEM_ALERT_WINDOW 오버레이는 백그라운드에서도 화면에 확실히 그려진다.
 *  - 게다가 창이 떠 있는 동안 프로세스가 "perceptible" 로 승격돼 얼려지지 않음
 *    → 단계 1 에서 Toast 가 씹혔던 문제(App Freezer)를 원천적으로 회피.
 *
 * Compose 대신 순수 View 로 그린다(오버레이에 Compose 를 얹으려면 별도 lifecycle owner
 * 배선이 필요해 MVP 에선 과함).
 */
object RatingOverlay {

    private const val TAG = "CallStar/Overlay"
    private const val AUTO_DISMISS_MS = 15_000L

    private var wm: WindowManager? = null
    private var root: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { remove() }

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 별점 팝업 표시.
     * @param recording 방금 통화 녹음(파일명 표시 + _ID 로 저장할 대상)
     * @param onRated 별점 클릭 콜백. null 이면 기본 동작으로 Room DB 저장([RatingStore]).
     *                rating ∈ {5,4,3,0}
     */
    @SuppressLint("InflateParams")
    fun show(
        context: Context,
        recording: CallRecording,
        onRated: ((recording: CallRecording, rating: Int) -> Unit)? = null,
    ) {
        handler.post { showInternal(context.applicationContext, recording, onRated) }
    }

    private fun showInternal(
        appCtx: Context,
        recording: CallRecording,
        onRated: ((CallRecording, Int) -> Unit)?,
    ) {
        if (!canDraw(appCtx)) {
            Log.w(TAG, "오버레이 권한 없음 → 표시 불가")
            return
        }
        remove() // 이전 팝업 있으면 치우고 새로

        // 콜백 없으면 기본 = Room 저장
        val effectiveOnRated: (CallRecording, Int) -> Unit =
            onRated ?: { r, s -> RatingStore.save(appCtx, r, s) }

        val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val card = buildCard(
            appCtx,
            recording,
            onRate = { rating ->
                try { effectiveOnRated(recording, rating) } catch (e: Exception) { Log.e(TAG, "onRated 실패", e) }
                showConfirmationThenRemove(rating)
            },
            onDelete = { launchDelete(appCtx, recording) },
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            (screenWidth(appCtx) * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 팝업 밖 화면은 정상 조작 가능하도록 NOT_FOCUSABLE
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(card, params)
            wm = windowManager
            root = card
            handler.removeCallbacks(autoDismiss)
            handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
            Log.d(TAG, "오버레이 표시: ${recording.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "addView 실패", e)
        }
    }

    fun remove() {
        handler.removeCallbacks(autoDismiss)
        val r = root
        val w = wm
        if (r != null && w != null) {
            try { w.removeView(r) } catch (e: Exception) { Log.e(TAG, "removeView 실패", e) }
        }
        root = null
        wm = null
    }

    /** 클릭 후 짧게 "저장됨" 보여주고 자동으로 닫기 (단계3에서 실제 저장으로 대체) */
    private fun showConfirmationThenRemove(rating: Int) {
        val r = root as? LinearLayout ?: run { remove(); return }
        r.removeAllViews()
        val ctx = r.context
        val c = palette(isDark(ctx))
        val backupOn = AppPrefs.isPremium(ctx) && AppPrefs.backupTreeUri(ctx) != null
        val label = when (rating) {
            Rating.KEEP -> "★ 중요 보관"
            Rating.LATER -> "△ 정리후보로 표시"
            else -> "기록됨"
        }
        val sub = when {
            rating == Rating.KEEP && backupOn -> "지정 폴더에 백업됨"
            rating == Rating.KEEP -> "백업은 프리미엄 (설정에서 폴더 지정)"
            else -> "저장됨"
        }
        r.addView(TextView(ctx).apply {
            text = "✓ $label"
            textSize = 16f
            setTextColor(c.okText)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        })
        r.addView(TextView(ctx).apply {
            text = sub
            textSize = 12f
            setTextColor(c.subtle)
            gravity = Gravity.CENTER
        })
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, 1200L)
    }

    /** "삭제" 버튼 → 오버레이 닫고 투명 액티비티로 시스템 삭제 확인창 실행 */
    private fun launchDelete(appCtx: Context, recording: CallRecording) {
        if (recording.id <= 0L) {
            Log.d(TAG, "샘플(id=${recording.id}) → 삭제 생략")
            remove()
            return
        }
        remove()
        try {
            appCtx.startActivity(DeleteRequestActivity.intent(appCtx, longArrayOf(recording.id)))
        } catch (e: Exception) {
            Log.e(TAG, "삭제 액티비티 시작 실패(백그라운드 제한?)", e)
        }
    }

    // ---- UI 빌더 ----

    private fun buildCard(
        ctx: Context,
        recording: CallRecording,
        onRate: (Int) -> Unit,
        onDelete: () -> Unit,
    ): LinearLayout {
        val dark = isDark(ctx)
        val c = palette(dark)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 16))
            background = GradientDrawable().apply {
                setColor(c.cardBg)
                cornerRadius = dp(ctx, 20).toFloat()
                setStroke(dp(ctx, 1), c.stroke)
            }
            elevation = dp(ctx, 12).toFloat()
        }

        card.addView(TextView(ctx).apply {
            text = "방금 통화 — 별점 남기기"
            textSize = 13f
            setTextColor(c.subtle)
        })
        card.addView(TextView(ctx).apply {
            text = recording.displayName
            textSize = 17f
            setTextColor(c.title)
            maxLines = 2
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 14))
        })

        // 평가 버튼 행: ★ 중요 / △ 정리후보
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(ratingButton(ctx, "★ 중요", "#F5B301") { onRate(Rating.KEEP) })
        row.addView(ratingButton(ctx, "△ 정리후보", "#78909C") { onRate(Rating.LATER) })
        card.addView(row)

        // 하단 행: 🗑 삭제(왼쪽) + 나중에(오른쪽)
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        bottom.addView(Button(ctx).apply {
            text = "🗑 삭제"
            textSize = 14f
            setTextColor(c.deleteTx)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(c.deleteBg)
                cornerRadius = dp(ctx, 12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f)
                .apply { setMargins(0, 0, dp(ctx, 6), 0) }
            setOnClickListener { onDelete() }
        })
        bottom.addView(Button(ctx).apply {
            text = "나중에"
            textSize = 14f
            setTextColor(c.laterTx)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(c.laterBg)
                cornerRadius = dp(ctx, 12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f)
                .apply { setMargins(dp(ctx, 6), 0, 0, 0) }
            setOnClickListener { remove() }
        })
        card.addView(bottom)

        return card
    }

    // ---- 테마(라이트/다크) ----

    private data class Palette(
        val cardBg: Int, val stroke: Int, val subtle: Int, val title: Int,
        val laterBg: Int, val laterTx: Int, val deleteBg: Int, val deleteTx: Int,
        val okText: Int,
    )

    private fun palette(dark: Boolean): Palette = if (dark) Palette(
        cardBg = Color.parseColor("#1E2624"),
        stroke = Color.parseColor("#33403C"),
        subtle = Color.parseColor("#9AA5A1"),
        title = Color.parseColor("#ECEFEE"),
        laterBg = Color.parseColor("#2A322F"),
        laterTx = Color.parseColor("#B0BAB6"),
        deleteBg = Color.parseColor("#3A1E1E"),
        deleteTx = Color.parseColor("#FF8A80"),
        okText = Color.parseColor("#81C784"),
    ) else Palette(
        cardBg = Color.WHITE,
        stroke = Color.parseColor("#E0E0E0"),
        subtle = Color.parseColor("#9E9E9E"),
        title = Color.parseColor("#212121"),
        laterBg = Color.parseColor("#F2F2F2"),
        laterTx = Color.parseColor("#757575"),
        deleteBg = Color.parseColor("#FDECEA"),
        deleteTx = Color.parseColor("#C62828"),
        okText = Color.parseColor("#1B5E20"),
    )

    private fun isDark(ctx: Context): Boolean = when (AppPrefs.themeModeKey(ctx)) {
        "dark" -> true
        "light" -> false
        else -> (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun ratingButton(
        ctx: Context,
        label: String,
        colorHex: String,
        onClick: () -> Unit,
    ): Button {
        return Button(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = dp(ctx, 12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                0, dp(ctx, 52), 1f,
            )
            lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics,
        ).toInt()

    private fun screenWidth(ctx: Context): Int =
        ctx.resources.displayMetrics.widthPixels
}
