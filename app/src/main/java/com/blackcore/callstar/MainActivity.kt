package com.blackcore.callstar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.blackcore.callstar.data.AppPrefs
import com.blackcore.callstar.data.CallListRepository
import com.blackcore.callstar.data.CallRow
import com.blackcore.callstar.data.Rating
import com.blackcore.callstar.data.RatingStore
import com.blackcore.callstar.ui.CallStarTheme
import com.blackcore.callstar.ui.DeleteRed
import com.blackcore.callstar.ui.LaterGray
import com.blackcore.callstar.ui.StarGold
import com.blackcore.callstar.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // 알림에서 눌러 진입한 순간을 알리는 시그널(값이 바뀌면 목록에서 대기 통화 강조).
    private val fromNotiSignal = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeSignalFromNoti(intent)
        setContent {
            val ctx = LocalContext.current
            var themeMode by remember { mutableStateOf(ThemeMode.fromKey(AppPrefs.themeModeKey(ctx))) }
            CallStarTheme(themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    Scaffold { inner ->
                        CallStarApp(
                            modifier = Modifier.padding(inner),
                            themeMode = themeMode,
                            onThemeChange = { m ->
                                themeMode = m
                                AppPrefs.setThemeModeKey(ctx, m.key)
                            },
                            fromNotiSignal = fromNotiSignal.value,
                        )
                    }
                }
            }
        }
    }

    // 이미 실행 중일 때 알림 탭으로 재진입하면 여기로 새 인텐트가 온다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeSignalFromNoti(intent)
    }

    private fun maybeSignalFromNoti(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_NOTI, false) == true) {
            fromNotiSignal.value = System.currentTimeMillis()
        }
    }

    companion object {
        const val EXTRA_FROM_NOTI = "from_noti"
    }
}

// ---- 권한 ----

private fun requiredPermissions(): Array<String> {
    val list = mutableListOf(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.READ_MEDIA_AUDIO)
        list.add(Manifest.permission.POST_NOTIFICATIONS)   // 통화 후 별점 알림
    } else {
        list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return list.toTypedArray()
}

/** 앱 동작에 필수인 권한(전화상태 + 오디오). 알림 권한은 필수가 아니라 별도. */
private fun essentialPermissions(): Array<String> {
    val list = mutableListOf(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return list.toTypedArray()
}

private fun allGranted(ctx: Context): Boolean =
    essentialPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

private val AUDIO_COLLECTION: Uri
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

@Composable
private fun CallStarApp(
    modifier: Modifier = Modifier,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    fromNotiSignal: Long = 0L,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(allGranted(ctx)) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    var rows by remember { mutableStateOf<List<CallRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var laterOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var status by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<List<Long>>(emptyList()) }
    var actionRow by remember { mutableStateOf<CallRow?>(null) }

    // 미리듣기 플레이어 (분류 팝업에서 사용)
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    var showSettings by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    // 알림 타고 들어왔을 때 "이 통화들이에요" 강조할 id 집합 + 세션 1회 판단 플래그
    var hlConsumedSignal by remember { mutableStateOf(0L) }
    var blinking by remember { mutableStateOf(false) }   // 알림 진입 시 "여기요~" 깜빡임 진행 중
    val hlPulse = remember { Animatable(1f) }            // 깜빡임 강도(0~1)
    var hlEnabled by remember { mutableStateOf(AppPrefs.highlightEnabled(ctx)) }
    var autoMarkEnabled by remember { mutableStateOf(AppPrefs.autoMarkEnabled(ctx)) }
    var autoKeywords by remember { mutableStateOf(AppPrefs.autoMarkKeywords(ctx)) }
    var isPremium by remember { mutableStateOf(AppPrefs.isPremium(ctx)) }
    var overlayMode by remember { mutableStateOf(AppPrefs.postCallOverlay(ctx)) }
    var backupFolder by remember {
        mutableStateOf(backupFolderName(ctx))
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                AppPrefs.setBackupTreeUri(ctx, uri.toString())
                backupFolder = backupFolderName(ctx)
            } catch (_: Exception) {
            }
        }
    }

    // 시스템 연락처 선택기: READ_CONTACTS 권한 없이, 사용자가 고른 한 명의 이름만 받아온다.
    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        val name = uri?.let { contactDisplayName(ctx, it) }?.trim()
        if (!name.isNullOrEmpty()) {
            autoKeywords = AppPrefs.addAutoMarkKeyword(ctx, name)
        }
    }

    fun refresh() {
        if (!allGranted(ctx)) return
        loading = true
        scope.launch {
            rows = CallListRepository.load(ctx)
            selected = selected intersect rows.map { it.recording.id }.toSet()
            // 앱에서 마킹/삭제한 건을 알림 대기열에도 반영(카운트/표시 동기화)
            NotificationHelper.reconcile(ctx, rows.filter { it.rating == null }.map { it.recording.id }.toSet())
            loading = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = allGranted(ctx); if (granted) refresh() }

    // 삭제 요청(시스템 확인 다이얼로그) 결과
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deleted = pendingDelete
        scope.launch {
            // 시스템이 실제 삭제를 마쳤으므로 DB 별점도 정리하고 목록 새로고침
            CallListRepository.forgetRatings(ctx, deleted)
            selected = selected - deleted.toSet()
            pendingDelete = emptyList()
            rows = CallListRepository.load(ctx)
            NotificationHelper.reconcile(ctx, rows.filter { it.rating == null }.map { it.recording.id }.toSet())
            status = if (result.resultCode == android.app.Activity.RESULT_OK)
                "삭제 완료" else "삭제 취소됨 (남아있는 파일은 그대로)"
        }
    }

    fun requestDelete(ids: List<Long>) {
        if (ids.isEmpty()) return
        pendingDelete = ids
        val uris = ids.map { ContentUris.withAppendedId(AUDIO_COLLECTION, it) }
        val pi = MediaStore.createDeleteRequest(ctx.contentResolver, uris)
        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    // 선택한 여러 통화에 별점 일괄 적용 (중요면 프리미엄 백업도 각각 시도)
    fun applyRatingToSelected(rating: Int) {
        val recs = rows.filter { it.recording.id in selected }.map { it.recording }
        if (recs.isEmpty()) return
        scope.launch {
            recs.forEach { RatingStore.saveNow(ctx, it, rating) }
            val n = recs.size
            selected = emptySet()
            rows = CallListRepository.load(ctx)
            NotificationHelper.reconcile(ctx, rows.filter { it.rating == null }.map { it.recording.id }.toSet())
            status = "$n 건을 " + if (rating == Rating.KEEP) "★ 중요로 표시" else "△ 정리후보로 표시"
        }
    }

    // 화면 복귀 시 권한/목록 갱신
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                granted = allGranted(ctx)
                canOverlay = Settings.canDrawOverlays(ctx)
                if (granted) refresh()
            } else if (e == Lifecycle.Event.ON_PAUSE) {
                player.stop()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(granted) { if (granted) refresh() }

    if (!granted) {
        PermissionGate(Modifier.then(modifier)) { permLauncher.launch(requiredPermissions()) }
        return
    }

    // 백업함 전용 화면 (설정과 분리된 전체화면 뷰어)
    if (showBackup) {
        BackupBrowser(
            modifier = modifier,
            onBack = { showBackup = false; refresh() },   // 원본 삭제 반영 위해 복귀 시 목록 갱신
        )
        return
    }

    val visible = if (laterOnly) rows.filter { it.rating == Rating.LATER } else rows
    val listState = rememberLazyListState()

    // 알림에서 눌러 진입할 때(설정 켜짐 시) "오늘 미분류" 통화로 스크롤 + 2번 반짝(총 2초).
    // 반짝임 후에는 정적 테두리가 그대로 유지된다(오늘 미분류 조건은 목록 렌더에서 판단).
    // fromNotiSignal 은 알림 탭 순간에만 갱신되므로 일반 실행/새로고침엔 반짝이지 않는다.
    LaunchedEffect(fromNotiSignal, rows) {
        if (fromNotiSignal > hlConsumedSignal && rows.isNotEmpty()) {
            hlConsumedSignal = fromNotiSignal
            if (!AppPrefs.highlightEnabled(ctx)) return@LaunchedEffect
            val firstIdx = visible.indexOfFirst {
                it.rating == null && DateUtils.isToday(it.recording.dateModifiedSec * 1000L)
            }
            if (firstIdx >= 0) {
                listState.animateScrollToItem(firstIdx)
                blinking = true
                hlPulse.snapTo(1f)
                repeat(2) {                       // 번쩍…번쩍 (2회, 약 2초)
                    hlPulse.animateTo(0f, tween(400))
                    hlPulse.animateTo(1f, tween(600))
                }
                blinking = false                  // 이후 정적 테두리로 정착
            }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("통화서랍", style = typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    // 버전 표시: 디버그는 빌드번호 포함, 릴리즈는 버전만
                    Text(
                        if (BuildConfig.DEBUG) {
                            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                        } else {
                            "v${BuildConfig.VERSION_NAME}"
                        },
                        style = typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "통화녹음 ${rows.size}건 · 평가됨 ${rows.count { it.rating != null }}건",
                    style = typography.bodySmall,
                )
            }
            IconButton(onClick = { refresh() }, enabled = !loading) {
                Text(if (loading) "…" else "↻", style = typography.titleLarge)
            }
            IconButton(onClick = { showSettings = !showSettings }) {
                Text("⚙", style = typography.titleLarge)
            }
        }

        if (showSettings) {
            SettingsPanel(
                isPremium = isPremium,
                backupFolder = backupFolder,
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                overlayMode = overlayMode,
                canOverlay = canOverlay,
                onToggleOverlayMode = {
                    overlayMode = !overlayMode
                    AppPrefs.setPostCallOverlay(ctx, overlayMode)
                },
                onGrantOverlay = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}"),
                        )
                    )
                },
                onPickFolder = { folderLauncher.launch(null) },
                onTogglePremium = {
                    isPremium = !isPremium
                    AppPrefs.setPremium(ctx, isPremium)
                },
                highlightEnabled = hlEnabled,
                onToggleHighlight = {
                    hlEnabled = !hlEnabled
                    AppPrefs.setHighlightEnabled(ctx, hlEnabled)
                    // 다시 켜면 앞으로 몇 번 더 강조해주도록 카운터 리셋
                    if (hlEnabled) AppPrefs.resetHighlightShownCount(ctx)
                },
                autoMarkEnabled = autoMarkEnabled,
                autoKeywords = autoKeywords,
                onToggleAutoMark = {
                    autoMarkEnabled = !autoMarkEnabled
                    AppPrefs.setAutoMarkEnabled(ctx, autoMarkEnabled)
                },
                onAddKeyword = { autoKeywords = AppPrefs.addAutoMarkKeyword(ctx, it) },
                onRemoveKeyword = { autoKeywords = AppPrefs.removeAutoMarkKeyword(ctx, it) },
                onPickContact = { contactLauncher.launch(null) },
                onOpenBackup = { showSettings = false; showBackup = true },
                modifier = Modifier.weight(1f),
            )
        } else {

        Spacer(Modifier.height(10.dp))
        // 컨트롤 바: 필터 토글 + 전체선택
        val visibleIds = visible.map { it.recording.id }.toSet()
        val allSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = laterOnly, onCheckedChange = { laterOnly = it })
            Spacer(Modifier.width(6.dp))
            Text("정리후보만 보기", style = typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    selected = if (allSelected) selected - visibleIds else selected + visibleIds
                },
                enabled = visibleIds.isNotEmpty(),
            ) { Text(if (allSelected) "전체해제" else "전체선택") }
        }

        // 안내/상태 (일괄 액션은 화면 하단 바로 이동)
        Text(
            if (status.isNotEmpty()) status
            else "항목 탭 = 재생·분류 · 체크 = 여러 개 골라 아래에서 일괄 처리",
            style = typography.bodySmall,
            color = Color(0xFF9E9E9E),
        )
        Spacer(Modifier.height(8.dp))

        // 목록: 남은 공간 채움
        if (visible.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (laterOnly) "정리후보로 표시된 통화녹음이 없어요"
                    else "통화녹음이 없어요",
                    style = typography.bodyMedium,
                    color = Color(0xFF9E9E9E),
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.recording.id }) { row ->
                    val isChecked = row.recording.id in selected
                    val todayUnmarked = row.rating == null &&
                        DateUtils.isToday(row.recording.dateModifiedSec * 1000L)
                    CallRowItem(
                        row = row,
                        checked = isChecked,
                        todayUnmarked = todayUnmarked,
                        blinking = blinking,
                        blinkAlpha = hlPulse.value,
                        onToggleSelect = {
                            selected = if (isChecked) selected - row.recording.id
                            else selected + row.recording.id
                        },
                        onOpen = { actionRow = row },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        // 하단 고정 일괄 액션 바 (체크 선택 시에만)
        if (selected.isNotEmpty()) {
            SelectionBar(
                count = selected.size,
                onClear = { selected = emptySet() },
                onKeep = { applyRatingToSelected(Rating.KEEP) },
                onLater = { applyRatingToSelected(Rating.LATER) },
                onDelete = { requestDelete(selected.toList()) },
            )
        }
        } // else (설정 열려있지 않을 때만 목록/일괄바 표시)
    }

    // 항목 탭 → 중요/정리후보/평가해제/삭제 지정
    actionRow?.let { row ->
        ItemActionDialog(
            row = row,
            player = player,
            onKeep = {
                scope.launch { RatingStore.saveNow(ctx, row.recording, Rating.KEEP); refresh() }
                actionRow = null
            },
            onLater = {
                scope.launch { RatingStore.saveNow(ctx, row.recording, Rating.LATER); refresh() }
                actionRow = null
            },
            onClearRating = {
                scope.launch { CallListRepository.forgetRatings(ctx, listOf(row.recording.id)); refresh() }
                actionRow = null
            },
            onDelete = {
                val id = row.recording.id
                actionRow = null
                requestDelete(listOf(id))
            },
            onDismiss = { actionRow = null },
        )
    }
}

/** 화면 하단 고정 일괄 액션 바 (체크 선택 시) */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onKeep: () -> Unit,
    onLater: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${count}건 선택됨", style = typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("해제") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("★ 중요") }
            OutlinedButton(onClick = onLater, modifier = Modifier.weight(1f)) { Text("△ 정리") }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                Text("🗑 삭제", color = DeleteRed)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ItemActionDialog(
    row: CallRow,
    player: AudioPlayer,
    onKeep: () -> Unit,
    onLater: () -> Unit,
    onClearRating: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0) }
    var durMs by remember { mutableStateOf(0) }

    // 다이얼로그 닫히면 재생 정지
    DisposableEffect(Unit) { onDispose { player.stop() } }

    // 재생 중 진행위치 갱신
    LaunchedEffect(playing) {
        while (playing) {
            posMs = player.positionMs()
            durMs = player.durationMs()
            kotlinx.coroutines.delay(250)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                row.recording.displayName
                    .removePrefix("통화 녹음 ")
                    .removePrefix("통화 "),
                style = typography.titleSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onKeep,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("★ 중요로 표시" + if (row.rating == Rating.KEEP) " (현재)" else "") }
                OutlinedButton(
                    onClick = onLater,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("△ 정리후보로 표시" + if (row.rating == Rating.LATER) " (현재)" else "") }
                if (row.rating != null) {
                    OutlinedButton(
                        onClick = onClearRating,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("평가 해제") }
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🗑 이 통화 삭제", color = DeleteRed) }

                Spacer(Modifier.height(4.dp))
                // 하단 라인: ▶ 재생하기(좌) ↔ 닫기(우)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        if (playing) {
                            player.stop(); playing = false; posMs = 0
                        } else {
                            player.play(ctx, row.recording.uri) { playing = false; posMs = 0 }
                            playing = true
                        }
                    }) { Text(if (playing) "⏸ 정지" else "▶ 재생하기") }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }

                // 재생바 + 시간 (재생 중이거나 탐색 위치가 있을 때) — 팝업 하단
                if (playing || posMs > 0) {
                    Slider(
                        value = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            if (durMs > 0) {
                                val target = (frac * durMs).toInt()
                                player.seekTo(target)
                                posMs = target
                            }
                        },
                    )
                    Text(
                        "${fmtClock(posMs)} / ${fmtClock(durMs)}",
                        style = typography.bodySmall,
                        color = Color(0xFF9E9E9E),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun CallRowItem(
    row: CallRow,
    checked: Boolean,
    todayUnmarked: Boolean,   // 오늘 통화인데 아직 미분류 → 테두리로 주목
    blinking: Boolean,        // 알림 진입 직후 "여기요~" 깜빡임 중
    blinkAlpha: Float,        // 깜빡임 강도(0~1)
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // 오늘 미분류면 테두리 표시. 알림 진입 직후엔 깜빡이고, 이후엔 그날 내내 정적 유지.
    val borderAlpha = if (todayUnmarked) (if (blinking) blinkAlpha else 1f) else 0f
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            if (todayUnmarked) Modifier.border(
                2.dp,
                accent.copy(alpha = borderAlpha.coerceIn(0f, 1f)),
                RoundedCornerShape(12.dp),
            )
            else Modifier
        )
        .clickable { onOpen() }   // 본문 탭 → 재생/중요/정리후보/삭제
    Card(cardModifier) {
        Row(
            Modifier.padding(start = 4.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggleSelect() })  // 체크 → 일괄 처리 선택
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // 기기별 접두어 정리: "통화 녹음 X" / "통화 X" → "X"
                    row.recording.displayName
                        .removePrefix("통화 녹음 ")
                        .removePrefix("통화 "),
                    style = typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 1줄: 연월일 / 2줄: 시각 · 길이
                Text(
                    formatYMD(row.recording.dateModifiedSec),
                    style = typography.bodySmall,
                    color = Color(0xFF9E9E9E),
                )
                Text(
                    "${formatHM(row.recording.dateModifiedSec)} · ${formatDurKo(row.recording.durationMs)}",
                    style = typography.bodySmall,
                    color = Color(0xFF9E9E9E),
                )
            }
            Spacer(Modifier.width(6.dp))
            RatingBadge(row.rating)
        }
    }
}

@Composable
private fun RatingBadge(rating: Int?) {
    val (text, color) = when (rating) {
        null -> "—" to Color(0xFFBDBDBD)
        Rating.KEEP -> "★ 중요" to StarGold
        Rating.LATER -> "△ 정리" to LaterGray
        else -> "$rating" to Color(0xFF9E9E9E)  // 레거시 값 대비
    }
    Box(
        Modifier
            .width(64.dp)
            .height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = color.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text, style = typography.bodySmall, color = color)
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    isPremium: Boolean,
    backupFolder: String?,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    overlayMode: Boolean,
    canOverlay: Boolean,
    onToggleOverlayMode: () -> Unit,
    onGrantOverlay: () -> Unit,
    onPickFolder: () -> Unit,
    onTogglePremium: () -> Unit,
    highlightEnabled: Boolean,
    onToggleHighlight: () -> Unit,
    autoMarkEnabled: Boolean,
    autoKeywords: List<String>,
    onToggleAutoMark: () -> Unit,
    onAddKeyword: (String) -> Unit,
    onRemoveKeyword: (String) -> Unit,
    onPickContact: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ══════════ 기본 기능 ══════════
            Text("통화 후 알림 방식", style = typography.titleMedium)
            ChoiceRow(
                selected = !overlayMode,
                title = "알림으로 남기기 (권장)",
                desc = "잠금화면·알림창에 남아, 나중에 봐도 바로 마킹",
                onClick = { if (overlayMode) onToggleOverlayMode() },
            )
            ChoiceRow(
                selected = overlayMode,
                title = "즉시 팝업",
                desc = "통화 끝나면 화면에 바로 팝업 (오버레이 권한 필요)",
                onClick = { if (!overlayMode) onToggleOverlayMode() },
            )
            if (overlayMode && !canOverlay) {
                OutlinedButton(onClick = onGrantOverlay, modifier = Modifier.fillMaxWidth()) {
                    Text("오버레이 권한 켜기")
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("알림 안내 강조", style = typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (highlightEnabled) "켜짐" else "꺼짐", style = typography.bodyMedium)
                    Text(
                        "오늘 아직 분류 안 한 통화를 테두리로 표시하고, 알림 타고 오면 2번 반짝여요",
                        style = typography.bodySmall,
                        color = Color(0xFF9E9E9E),
                    )
                }
                Switch(checked = highlightEnabled, onCheckedChange = { onToggleHighlight() })
            }

            Spacer(Modifier.height(6.dp))
            Text("화면 테마", style = typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    if (m == themeMode) {
                        Button(onClick = { }, modifier = Modifier.weight(1f)) { Text(m.label) }
                    } else {
                        OutlinedButton(onClick = { onThemeChange(m) }, modifier = Modifier.weight(1f)) {
                            Text(m.label)
                        }
                    }
                }
            }

            // ══════════ 프리미엄 기능 (별도 박스) ══════════
            Spacer(Modifier.height(12.dp))
            val accent = MaterialTheme.colorScheme.primary
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.06f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⭐ 프리미엄 기능",
                            style = typography.titleMedium,
                            color = accent,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = isPremium, onCheckedChange = { onTogglePremium() })
                    }
                    Text(
                        if (isPremium) "켜짐 · 아래 기능이 동작해요"
                        else "꺼짐 · 켜면 아래 기능이 동작해요 (지금은 임시 토글, 결제 미연동)",
                        style = typography.bodySmall,
                        color = Color(0xFF9E9E9E),
                    )

                    HorizontalDivider(Modifier.padding(vertical = 2.dp))

                    // 자동 백업
                    Text("중요 통화 자동 백업", style = typography.titleSmall)
                    Text(
                        "★중요로 표시하면 원본은 그대로 두고 지정 폴더로 복사돼요.",
                        style = typography.bodySmall,
                        color = Color(0xFF757575),
                    )
                    Text("백업 폴더: " + (backupFolder ?: "미지정"), style = typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickFolder, modifier = Modifier.weight(1f)) {
                            Text("백업 폴더 지정")
                        }
                        Button(
                            onClick = onOpenBackup,
                            enabled = backupFolder != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("백업한 통화 보기") }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 2.dp))

                    // 자동 중요 번호/이름
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("자동 중요 번호/이름", style = typography.titleSmall)
                            Text(
                                "등록한 이름/번호와의 통화는 묻지 않고 ★중요로 표시(+백업)",
                                style = typography.bodySmall,
                                color = Color(0xFF9E9E9E),
                            )
                        }
                        Switch(checked = autoMarkEnabled, onCheckedChange = { onToggleAutoMark() })
                    }
                    OutlinedButton(onClick = onPickContact, modifier = Modifier.fillMaxWidth()) {
                        Text("📇 연락처에서 선택")
                    }
                    var kwInput by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = kwInput,
                            onValueChange = { kwInput = it },
                            singleLine = true,
                            label = { Text("직접 입력 (이름·번호)") },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onAddKeyword(kwInput); kwInput = "" },
                            enabled = kwInput.isNotBlank(),
                        ) { Text("추가") }
                    }
                    if (autoKeywords.isEmpty()) {
                        Text(
                            "아직 등록된 항목이 없어요. 예: 큰형, 회사, 01012345678",
                            style = typography.bodySmall,
                            color = Color(0xFF9E9E9E),
                        )
                    } else {
                        autoKeywords.forEach { kw ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("• $kw", style = typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onRemoveKeyword(kw) }) { Text("삭제") }
                            }
                        }
                    }
                    Text(
                        "연락처에서 고르면 그 이름과 ‘정확히 일치’하는 통화만 자동 처리돼요. 저장 안 된 번호는 직접 입력하세요.",
                        style = typography.bodySmall,
                        color = Color(0xFF757575),
                    )
                }
            }
        }
    }
}

/** 설정의 라디오형 선택 카드 (알림 방식 등 이지선다). 고른 쪽이 강조된다. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
        ),
        border = if (selected) BorderStroke(2.dp, accent)
        else BorderStroke(1.dp, Color(0x33808080)),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = typography.bodyLarge)
                Text(desc, style = typography.bodySmall, color = Color(0xFF9E9E9E))
            }
        }
    }
}

/** 백업 폴더 안의 한 파일. */
private data class BackupFile(
    val doc: DocumentFile,
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/**
 * 백업함 — 프리미엄 백업 폴더 전용 뷰어(설정과 분리된 전체화면).
 * SAF 영구권한으로 폴더를 읽어 목록 표시 → 재생 청취 → 필요 없으면 백업본만 삭제.
 * (원본 통화녹음과 평가는 건드리지 않는다. 여기 삭제 = 백업 복사본만 제거.)
 */
@Composable
private fun BackupBrowser(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val treeStr = remember { AppPrefs.backupTreeUri(ctx) }
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    var files by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var activeUri by remember { mutableStateOf<String?>(null) }   // 펼쳐진(선택된) 행
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0) }
    var durMs by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<BackupFile?>(null) }   // 원본 없는 경우: 백업본만 삭제 확인
    // 원본까지 지울 때 쓰는 시스템 삭제창 대기 상태 (백업본, 원본 id)
    var pendingFull by remember { mutableStateOf<Pair<BackupFile, Long>?>(null) }

    fun stopPlay() { player.stop(); playing = false; posMs = 0; durMs = 0 }

    // 원본(MediaStore) 삭제 시스템 확인창 결과
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pend = pendingFull
        pendingFull = null
        if (pend != null && result.resultCode == android.app.Activity.RESULT_OK) {
            val (bf, originalId) = pend
            if (activeUri == bf.doc.uri.toString()) stopPlay()
            scope.launch {
                withContext(Dispatchers.IO) {
                    try { bf.doc.delete() } catch (_: Exception) {}   // 백업본 조용히 삭제
                }
                CallListRepository.forgetRatings(ctx, listOf(originalId))   // ★중요 평가도 해제
                files = files.filterNot { it.doc.uri == bf.doc.uri }
            }
        }
    }

    // 🗑 삭제 요청: 원본 있으면 시스템창(원본+백업+평가), 없으면 백업본만 확인 후 삭제
    fun requestFullDelete(f: BackupFile) {
        scope.launch {
            val original = withContext(Dispatchers.IO) {
                CallRecordingFinder.findByDisplayName(ctx, f.name)
            }
            if (original != null) {
                pendingFull = f to original.id
                val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(original.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                confirmDelete = f   // 원본 없음(백업본이 유일본) → 자체 확인창
            }
        }
    }

    LaunchedEffect(reload) {
        loading = true
        files = withContext(Dispatchers.IO) {
            if (treeStr == null) return@withContext emptyList()
            try {
                DocumentFile.fromTreeUri(ctx, Uri.parse(treeStr))
                    ?.listFiles()
                    ?.filter { it.isFile && isAudioBackup(it) }   // 통화녹음(오디오)만 — 폴더에 다른 파일 섞여도 무시
                    ?.map { BackupFile(it, it.name ?: "(이름 없음)", it.length(), it.lastModified()) }
                    ?.sortedByDescending { it.modifiedMs }
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        loading = false
    }

    LaunchedEffect(playing) {
        while (playing) {
            posMs = player.positionMs(); durMs = player.durationMs()
            kotlinx.coroutines.delay(250)
        }
    }

    val filtered = if (query.isBlank()) files
        else files.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { stopPlay(); onBack() }) {
                Text("←", style = typography.titleLarge)
            }
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f)) {
                Text("백업함", style = typography.headlineSmall)
                Text(
                    "★중요로 백업된 통화 · 들어보고 필요 없으면 삭제",
                    style = typography.bodySmall,
                    color = Color(0xFF9E9E9E),
                )
            }
            IconButton(onClick = { reload++ }, enabled = !loading) {
                Text(if (loading) "…" else "↻", style = typography.titleLarge)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("이름 검색") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )

        when {
            treeStr == null -> CenterNote("백업 폴더가 지정되지 않았어요.\n설정에서 먼저 지정하세요.")
            loading -> CenterNote("불러오는 중…")
            filtered.isEmpty() -> CenterNote(
                if (files.isEmpty()) "백업된 통화가 없어요." else "검색 결과가 없어요.",
            )
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.doc.uri.toString() }) { f ->
                    val uriStr = f.doc.uri.toString()
                    val isActive = activeUri == uriStr
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            stopPlay()
                            activeUri = if (isActive) null else uriStr
                        },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                f.name.removePrefix("통화 녹음 ").removePrefix("통화 "),
                                style = typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatYMD(f.modifiedMs / 1000)} · ${fmtSize(f.sizeBytes)}",
                                style = typography.bodySmall,
                                color = Color(0xFF9E9E9E),
                            )
                            if (isActive) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = {
                                        if (playing) {
                                            player.stop(); playing = false; posMs = 0
                                        } else {
                                            player.play(ctx, f.doc.uri) { playing = false; posMs = 0 }
                                            playing = true
                                        }
                                    }) { Text(if (playing) "⏸ 정지" else "▶ 재생하기") }
                                    TextButton(onClick = { requestFullDelete(f) }) {
                                        Text("🗑 삭제", color = DeleteRed)
                                    }
                                }
                                if (playing || posMs > 0) {
                                    Slider(
                                        value = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
                                        onValueChange = { frac ->
                                            if (durMs > 0) {
                                                val t = (frac * durMs).toInt()
                                                player.seekTo(t); posMs = t
                                            }
                                        },
                                    )
                                    Text(
                                        "${fmtClock(posMs)} / ${fmtClock(durMs)}",
                                        style = typography.bodySmall,
                                        color = Color(0xFF9E9E9E),
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    // 원본이 이미 없는 경우(백업본이 유일본): 자체 확인 후 백업본만 삭제
    confirmDelete?.let { f ->
        val shortName = f.name.removePrefix("통화 녹음 ").removePrefix("통화 ")
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("백업본 삭제") },
            text = {
                Text(
                    "‘$shortName’ 을(를) 삭제할까요?\n" +
                        "원본은 이미 없고 이 백업본이 유일해요. 되돌릴 수 없어요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = f
                    confirmDelete = null
                    if (activeUri == target.doc.uri.toString()) stopPlay()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try { target.doc.delete() } catch (e: Exception) { false }
                        }
                        if (ok) files = files.filterNot { it.doc.uri == target.doc.uri }
                        else reload++   // 실패 시 재조회로 상태 맞춤
                    }
                }) { Text("삭제", color = DeleteRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("취소") }
            },
        )
    }
}

/** 백업함의 빈/로딩/무결과 안내 (가운데 정렬). */
@Composable
private fun ColumnScope.CenterNote(text: String) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = typography.bodyMedium,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center,
        )
    }
}

/** 백업 폴더에 다른 파일이 섞여 있어도 통화녹음(오디오)만 골라낸다. */
private fun isAudioBackup(doc: DocumentFile): Boolean {
    if (doc.type?.startsWith("audio/") == true) return true
    val n = (doc.name ?: "").lowercase()
    return n.endsWith(".m4a") || n.endsWith(".mp3") || n.endsWith(".amr") ||
        n.endsWith(".aac") || n.endsWith(".wav") || n.endsWith(".3gp") || n.endsWith(".ogg")
}

/** 파일 크기 사람이 읽기 좋게. */
private fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.0fKB".format(kb) else "%.1fMB".format(kb / 1024.0)
}

/** 연락처 선택기에서 받은 URI로 표시 이름만 조회(READ_CONTACTS 불필요 — 선택 항목에 임시 접근 허용). */
private fun contactDisplayName(context: Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

/** 백업 폴더의 표시 이름 (트리 URI → 폴더명) */
private fun backupFolderName(context: Context): String? {
    val s = AppPrefs.backupTreeUri(context) ?: return null
    return try {
        DocumentFile.fromTreeUri(context, Uri.parse(s))?.name ?: s
    } catch (_: Exception) {
        s
    }
}

@Composable
private fun PermissionGate(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("CallStar", style = typography.headlineSmall)
        Text(
            "통화 상태 감지와 통화녹음 읽기 권한이 필요해요.",
            style = typography.bodyMedium,
        )
        Button(onClick = onRequest) { Text("권한 요청") }
    }
}

// ---- 포맷 ----

private fun formatYMD(sec: Long): String =
    if (sec <= 0) "-"
    else SimpleDateFormat("yyyy년 MM월 dd일", Locale.KOREA).format(Date(sec * 1000L))

private fun formatHM(sec: Long): String =
    if (sec <= 0) "-"
    else SimpleDateFormat("HH시 mm분", Locale.KOREA).format(Date(sec * 1000L))

private fun formatDurKo(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}분 ${s}초" else "${s}초"
}

/** 재생바 시간 표기 m:ss */
private fun fmtClock(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
