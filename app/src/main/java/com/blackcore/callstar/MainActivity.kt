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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        )
                    }
                }
            }
        }
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
    // 알림 타고 들어왔을 때 "이 통화들이에요" 강조할 id 집합 + 세션 1회 판단 플래그
    var highlightIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var hlConsidered by remember { mutableStateOf(false) }
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

    val visible = if (laterOnly) rows.filter { it.rating == Rating.LATER } else rows
    val listState = rememberLazyListState()

    // 알림 타고 들어온 경우: 알림 대기열에 남은 통화들을 목록에서 강조해 "여기예요" 안내.
    // 시각 공해 방지를 위해 최초 몇 번만(HL_AUTO_MAX) 자동으로, 이후엔 설정에서 켜야 함.
    LaunchedEffect(rows) {
        if (!hlConsidered && rows.isNotEmpty()) {
            hlConsidered = true
            if (AppPrefs.highlightEnabled(ctx) &&
                AppPrefs.highlightShownCount(ctx) < AppPrefs.HL_AUTO_MAX
            ) {
                val pending = NotificationHelper.pendingIds(ctx).toSet()
                val present = rows.map { it.recording.id }.filter { it in pending }.toSet()
                if (present.isNotEmpty()) {
                    AppPrefs.bumpHighlightShownCount(ctx)
                    highlightIds = present
                    // 첫 강조 항목으로 스르륵 스크롤
                    val idx = visible.indexOfFirst { it.recording.id in present }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                    // 잠시 뒤 강조 해제(주목만 시키고 빠짐)
                    delay(6000L)
                    highlightIds = emptySet()
                }
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
                    CallRowItem(
                        row = row,
                        checked = isChecked,
                        highlighted = row.recording.id in highlightIds,
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
    highlighted: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            // 알림 안내 강조: 테두리 선 + 왼쪽 굵은 강조바로 "이 통화예요" 주목
            if (highlighted) Modifier.border(2.dp, accent, RoundedCornerShape(12.dp))
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
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("통화 후 별점 표시", style = typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (overlayMode) "즉시 팝업(오버레이)" else "알림 (권장)",
                        style = typography.bodyMedium,
                    )
                    Text(
                        if (overlayMode) "통화 끝나면 화면에 바로 팝업"
                        else "알림창·잠금화면에 남아 나중에도 마킹",
                        style = typography.bodySmall,
                        color = Color(0xFF9E9E9E),
                    )
                }
                Switch(checked = overlayMode, onCheckedChange = { onToggleOverlayMode() })
            }
            if (overlayMode && !canOverlay) {
                OutlinedButton(onClick = onGrantOverlay) { Text("오버레이 권한 켜기") }
            }

            Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(8.dp))
            Text("중요 통화 백업 (프리미엄)", style = typography.titleMedium)
            Text(
                "★중요로 표시하면 원본은 그대로 두고 지정 폴더로 복사돼요. " +
                    "잡통화 즉시 삭제는 무료, 자동 백업은 프리미엄.",
                style = typography.bodySmall,
                color = Color(0xFF757575),
            )
            Text(
                "백업 폴더: " + (backupFolder ?: "미지정"),
                style = typography.bodyMedium,
            )
            OutlinedButton(onClick = onPickFolder) { Text("백업 폴더 지정") }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isPremium) "프리미엄: 켜짐" else "프리미엄: 꺼짐",
                    style = typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = isPremium, onCheckedChange = { onTogglePremium() })
            }
            Text(
                "※ 지금은 임시 토글(결제 미연동). 나중에 Play 결제로 대체.",
                style = typography.bodySmall,
                color = Color(0xFF9E9E9E),
            )

            Spacer(Modifier.height(8.dp))
            Text("자동 중요 번호/이름 (프리미엄)", style = typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (autoMarkEnabled) "켜짐" else "꺼짐",
                        style = typography.bodyMedium,
                    )
                    Text(
                        "등록한 이름/번호와의 통화는 묻지 않고 ★중요로 표시(+백업)해요",
                        style = typography.bodySmall,
                        color = Color(0xFF9E9E9E),
                    )
                }
                Switch(checked = autoMarkEnabled, onCheckedChange = { onToggleAutoMark() })
            }
            if (!isPremium) {
                Text(
                    "프리미엄을 켜야 실제로 동작해요.",
                    style = typography.bodySmall,
                    color = Color(0xFF757575),
                )
            }
            // 연락처에서 선택(권한 없이 시스템 선택기) — 저장된 이름 정확히 등록
            OutlinedButton(onClick = onPickContact, modifier = Modifier.fillMaxWidth()) {
                Text("📇 연락처에서 선택")
            }
            // 직접 입력(미저장 번호 등)
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
            // 등록 목록
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
                "연락처에서 고르면 그 이름과 ‘정확히 일치’하는 통화만 자동 처리돼요(비슷한 이름 오작동 없음). " +
                    "저장 안 된 번호는 직접 입력하세요.",
                style = typography.bodySmall,
                color = Color(0xFF757575),
            )

            Spacer(Modifier.height(8.dp))
            Text("알림 안내 강조", style = typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (highlightEnabled) "켜짐" else "꺼짐",
                        style = typography.bodyMedium,
                    )
                    Text(
                        "알림 타고 들어오면 해당 통화를 목록에서 잠깐 강조해요",
                        style = typography.bodySmall,
                        color = Color(0xFF9E9E9E),
                    )
                }
                Switch(checked = highlightEnabled, onCheckedChange = { onToggleHighlight() })
            }
        }
    }
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
