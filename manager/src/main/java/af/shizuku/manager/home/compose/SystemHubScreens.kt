package af.shizuku.manager.home.compose

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityEventType
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.ActivityLogRecord
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.HapticUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.*

@Composable
fun ServerMetricsScreen() {
    val context = LocalContext.current
    var uptimeText by remember { mutableStateOf("00:00:00") }
    var clientCountText by remember { mutableStateOf(context.getString(R.string.server_clients_connected, 0)) }
    var memoryProgress by remember { mutableFloatStateOf(0f) }
    var memoryDetails by remember { mutableStateOf("0 MB / 0 MB") }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                if (Shizuku.pingBinder()) {
                    val binder = Shizuku.getBinder()
                    val shizukuService =
                        moe.shizuku.server.IShizukuService.Stub
                            .asInterface(binder)
                    var ai: af.shizuku.server.IAICorePlus? = shizukuService.aiCorePlus
                    if (ai == null) {
                        withContext(Dispatchers.IO) {
                            ShizukuSettings.syncAllPlusFeaturesToServer()
                        }
                        ai = shizukuService.aiCorePlus
                    }
                    if (ai == null) {
                        // Null when connected to a stock/mismatched server that doesn't implement
                        // the Plus AICore extension (SHIZUKUPLUS-6J) - not an error, just unavailable.
                        delay(1000)
                        continue
                    }
                    val stats =
                        ai.serverStats ?: run {
                            delay(1000)
                            continue
                        }

                    val uptimeMs = stats.getLong("uptime_ms")
                    val seconds = (uptimeMs / 1000) % 60
                    val minutes = (uptimeMs / (1000 * 60)) % 60
                    val hours = (uptimeMs / (1000 * 60 * 60)) % 24
                    val days = (uptimeMs / (1000 * 60 * 60 * 24))
                    uptimeText =
                        if (days > 0) {
                            String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds)
                        } else {
                            String.format("%02d:%02d:%02d", hours, minutes, seconds)
                        }

                    clientCountText = context.getString(R.string.server_clients_connected, stats.getInt("client_count"))

                    val maxRaw = stats.getLong("mem_max")
                    val total = stats.getLong("mem_total")
                    val free = stats.getLong("mem_free")
                    val used = (total - free).coerceAtLeast(0L)
                    val max = if (maxRaw == Long.MAX_VALUE || maxRaw <= 0) total else maxRaw

                    memoryProgress = if (max > 0) (used.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f

                    val formatSize = { bytes: Long ->
                        val kb = bytes / 1024
                        val mb = kb / 1024
                        val gb = mb / 1024
                        if (gb > 0) {
                            "$gb GB"
                        } else if (mb > 0) {
                            "$mb MB"
                        } else {
                            "$kb KB"
                        }
                    }
                    val maxStr = if (maxRaw == Long.MAX_VALUE) context.getString(R.string.server_memory_uncapped) else formatSize(max)
                    memoryDetails = "${formatSize(used)} / $maxStr (Max Allowed)"
                }
            } catch (e: Exception) {
                Timber.w(e)
            }
            delay(1000)
        }
    }

    val isOneHanded =
        af.shizuku.manager.ShizukuSettings
            .isOneHandedModeEnabled()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val oneHandedTop = if (isOneHanded) (screenHeightDp * 0.16f).dp else 0.dp

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .padding(top = oneHandedTop),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MetricCard(title = stringResource(R.string.server_uptime), value = uptimeText)
        MetricCard(title = stringResource(R.string.active_connections), value = clientCountText)
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(stringResource(R.string.server_memory_usage), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { memoryProgress },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(memoryDetails, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private enum class ActivityLogSort(
    val labelRes: Int,
) {
    NEWEST(R.string.changelog_sort_newest),
    OLDEST(R.string.changelog_sort_oldest),
    BY_APP(R.string.activity_log_sort_by_app),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
    showTopBar: Boolean = false,
    onBackClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val rawLogs by ActivityLogManager.logs.collectAsState(initial = emptyList())
    var selectedType by remember { mutableStateOf<ActivityEventType?>(null) }
    var currentSort by remember { mutableStateOf(ActivityLogSort.NEWEST) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val filteredLogs =
        remember(rawLogs, selectedType, currentSort) {
            val list = if (selectedType == null) rawLogs else rawLogs.filter { it.eventType == selectedType }
            when (currentSort) {
                ActivityLogSort.NEWEST -> list.sortedByDescending { it.timestamp }
                ActivityLogSort.OLDEST -> list.sortedBy { it.timestamp }
                ActivityLogSort.BY_APP -> list.sortedWith(compareBy({ it.appName.lowercase() }, { -it.timestamp }))
            }
        }

    val dateFormat =
        remember {
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT,
                Locale.getDefault(),
            )
        }

    val isOneHanded =
        af.shizuku.manager.ShizukuSettings
            .isOneHandedModeEnabled()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val oneHandedTop = if (isOneHanded) (screenHeightDp * 0.16f).dp else 0.dp

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.activity_log_clear_confirm_title)) },
            text = { Text(stringResource(R.string.activity_log_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        ActivityLogManager.clear()
                        showClearDialog = false
                    },
                ) {
                    Text(stringResource(R.string.settings_activity_log_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    val content = @Composable {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = if (showTopBar) 0.dp else oneHandedTop),
        ) {
            // Filter and Sort Chips Row (Morphe style)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Sort Dropdown Chip
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { showSortMenu = true },
                        label = { Text(stringResource(currentSort.labelRes)) },
                        trailingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_outline_expand_more_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        ActivityLogSort.values().forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(stringResource(sort.labelRes)) },
                                onClick = {
                                    currentSort = sort
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }

                // "All" Chip
                FilterChip(
                    selected = selectedType == null,
                    onClick = { selectedType = null },
                    label = { Text(stringResource(R.string.activity_log_filter_all)) },
                )

                // Event Type Chips
                ActivityEventType.values().forEach { type ->
                    val labelRes =
                        when (type) {
                            ActivityEventType.SERVICE_START -> R.string.activity_log_type_start
                            ActivityEventType.SERVICE_STOP -> R.string.activity_log_type_stop
                            ActivityEventType.CONNECTION -> R.string.activity_log_type_connection
                            ActivityEventType.PERMISSION -> R.string.activity_log_type_permission
                            ActivityEventType.APP_MANAGEMENT -> R.string.activity_log_type_app_mgmt
                            ActivityEventType.WATCHDOG -> R.string.activity_log_type_watchdog
                            ActivityEventType.SYSTEM -> R.string.activity_log_type_system
                            ActivityEventType.OTHER -> R.string.activity_log_type_other
                        }
                    FilterChip(
                        selected = selectedType == type,
                        onClick = {
                            selectedType = if (selectedType == type) null else type
                        },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(R.drawable.ic_history_24),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.empty_state_title_activity_log_empty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.empty_state_description_activity_log_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    items(
                        count = filteredLogs.size,
                        key = { i -> "${filteredLogs[i].timestamp}_${filteredLogs[i].packageName}_$i" },
                    ) { index ->
                        val record = filteredLogs[index]
                        ActivityLogTimelineRow(
                            record = record,
                            dateFormat = dateFormat,
                            isFirst = index == 0,
                            isLast = index == filteredLogs.lastIndex,
                            isLatest = index == 0 && currentSort == ActivityLogSort.NEWEST,
                        )
                    }
                }
            }
        }
    }

    if (showTopBar) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_show_activity_log_home)) },
                    navigationIcon = {
                        if (onBackClick != null) {
                            IconButton(onClick = onBackClick) {
                                Icon(painterResource(R.drawable.ic_back_24), contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (rawLogs.isNotEmpty()) {
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_delete_24),
                                    contentDescription = stringResource(R.string.settings_activity_log_clear),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    } else {
        content()
    }
}

@Composable
private fun ActivityLogTimelineRow(
    record: ActivityLogRecord,
    dateFormat: java.text.DateFormat,
    isFirst: Boolean,
    isLast: Boolean,
    isLatest: Boolean,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    var isExpanded by remember { mutableStateOf(false) }

    val chevronRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(if (ShizukuSettings.isExpressiveAnimationsEnabled()) 260 else 0),
        label = "chevronRotation",
    )

    var appName by remember(record.packageName) {
        mutableStateOf(record.appName.ifEmpty { record.packageName })
    }
    var iconBitmap by remember(record.packageName) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }

    LaunchedEffect(record.packageName) {
        val pm = context.packageManager
        val sizePx = with(density) { 36.dp.roundToPx() }
        val ai =
            withContext(Dispatchers.IO) {
                try {
                    pm.getApplicationInfo(record.packageName, 0)
                } catch (_: Exception) {
                    null
                }
            } ?: return@LaunchedEffect
        appName = AppIconCache.getLabel(context, ai)
        val bitmap =
            withContext(Dispatchers.IO) {
                AppIconCache.getOrLoadBitmap(context, ai, ai.uid / 100000, sizePx)
            }
        if (bitmap != null) iconBitmap = bitmap.asImageBitmap()
    }

    val typeColor =
        when (record.eventType) {
            ActivityEventType.SERVICE_START -> MaterialTheme.colorScheme.primary
            ActivityEventType.SERVICE_STOP -> MaterialTheme.colorScheme.error
            ActivityEventType.CONNECTION -> MaterialTheme.colorScheme.secondary
            ActivityEventType.PERMISSION -> MaterialTheme.colorScheme.tertiary
            ActivityEventType.APP_MANAGEMENT -> MaterialTheme.colorScheme.secondary
            ActivityEventType.WATCHDOG -> MaterialTheme.colorScheme.error
            ActivityEventType.SYSTEM -> MaterialTheme.colorScheme.outline
            ActivityEventType.OTHER -> MaterialTheme.colorScheme.outline
        }

    val typeLabel =
        when (record.eventType) {
            ActivityEventType.SERVICE_START -> stringResource(R.string.activity_log_type_start)
            ActivityEventType.SERVICE_STOP -> stringResource(R.string.activity_log_type_stop)
            ActivityEventType.CONNECTION -> stringResource(R.string.activity_log_type_connection)
            ActivityEventType.PERMISSION -> stringResource(R.string.activity_log_type_permission)
            ActivityEventType.APP_MANAGEMENT -> stringResource(R.string.activity_log_type_app_mgmt)
            ActivityEventType.WATCHDOG -> stringResource(R.string.activity_log_type_watchdog)
            ActivityEventType.SYSTEM -> stringResource(R.string.activity_log_type_system)
            ActivityEventType.OTHER -> stringResource(R.string.activity_log_type_other)
        }

    val railColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val isExpressive = ShizukuSettings.isExpressiveAnimationsEnabled()
    val haloTransition = rememberInfiniteTransition(label = "haloTransition")
    val pulseScale by
        if (isLatest && isExpressive) {
            haloTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.5f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(1400),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "haloScale",
            )
        } else {
            remember { mutableFloatStateOf(1f) }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        // Vertical connecting timeline rail matching Changelog TimelineRailView
        Canvas(
            modifier =
                Modifier
                    .width(28.dp)
                    .defaultMinSize(minHeight = 76.dp),
        ) {
            val cx = size.width / 2f
            val nodeY = 22.dp.toPx()
            val railStroke = 2.dp.toPx()

            // Continuous connecting rail
            val startY = if (isFirst) nodeY else 0f
            val endY = if (isLast) nodeY else size.height
            if (endY > startY) {
                drawLine(
                    color = railColor,
                    start = Offset(cx, startY),
                    end = Offset(cx, endY),
                    strokeWidth = railStroke,
                )
            }

            // Outer glowing halo (matching Changelog TimelineRailView 10dp * scale)
            val haloRadius = 10.dp.toPx() * if (isLatest) pulseScale else 1f
            drawCircle(
                color = typeColor.copy(alpha = if (isLatest) 0.32f else 0.16f),
                radius = haloRadius,
                center = Offset(cx, nodeY),
            )

            // Outer colored border / ring (matching TimelineRailView 6dp outer ring)
            drawCircle(
                color = typeColor,
                radius = 6.dp.toPx(),
                center = Offset(cx, nodeY),
            )

            // Inner center dot (matching filled / hollow node style)
            if (!isLatest) {
                drawCircle(
                    color = railColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(cx, nodeY),
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // M3E expressive Card with interactive elevation & animated expansion
        Card(
            shape = RoundedCornerShape(18.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                ),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(bottom = 10.dp)
                    .animateContentSize(
                        animationSpec = tween(if (isExpressive) 260 else 0),
                    ).clickable {
                        HapticUtils.segmentTick(view)
                        isExpanded = !isExpanded
                    },
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val bitmap = iconBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_system_icon),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = typeColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = dateFormat.format(Date(record.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Event type status badge (matching Changelog pill badges)
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = typeColor.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = typeColor,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Interactive chevron indicator (matching Changelog release expandable row)
                    Icon(
                        painter = painterResource(R.drawable.ic_outline_expand_more_24),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(22.dp)
                                .graphicsLayer(rotationZ = chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = record.action,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Expandable details (package name, copy button)
                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                    ) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = record.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    HapticUtils.tap(view)
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Package Name", record.packageName))
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.activity_log_copied, record.packageName),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_copy),
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
