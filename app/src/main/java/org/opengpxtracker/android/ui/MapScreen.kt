// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.opengpxtracker.android.location.GnssTelemetry
import org.opengpxtracker.android.R
import org.opengpxtracker.android.location.GpsSignalLevel
import org.opengpxtracker.android.location.LocationSample
import org.opengpxtracker.android.track.RecordingMode
import org.opengpxtracker.android.track.RecordingService
import org.opengpxtracker.android.track.TrackRecording
import org.opengpxtracker.android.track.TrackSummary
import org.opengpxtracker.android.track.DefaultGpxExporter
import org.opengpxtracker.android.track.GpxFileNames
import org.opengpxtracker.android.track.GpxSavedFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val OpenStreetMapCopyrightUrl = "https://www.openstreetmap.org/copyright"
private const val LocationSourceId = "current-location-source"
private const val LocationLayerId = "current-location-layer"
private const val AccuracySourceId = "location-accuracy-source"
private const val AccuracyLayerId = "location-accuracy-layer"
private const val TrackSourceId = "recording-track-source"
private const val TrackLayerId = "recording-track-layer"

private fun mapStyle(topographic: Boolean) = """
    {
      "version": 8,
      "name": "${if (topographic) "OpenTopoMap" else "OpenStreetMap"}",
      "sources": {
        "basemap": {
          "type": "raster",
          "tiles": ["${if (topographic) "https://a.tile.opentopomap.org/{z}/{x}/{y}.png" else "https://tile.openstreetmap.org/{z}/{x}/{y}.png"}"],
          "tileSize": 256,
          "attribution": "${if (topographic) "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)" else "© OpenStreetMap contributors"}",
          "maxzoom": ${if (topographic) 17 else 19}
        }
      },
      "layers": [
        {
          "id": "basemap",
          "type": "raster",
          "source": "basemap"
        }
      ]
    }
""".trimIndent()

@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val currentRecording by rememberUpdatedState(uiState.recording)
    val controlLock = uiState.controlLockState.takeIf { it.trackId == uiState.recording.id }
    val controlsLocked = controlLock?.isLocked == true
    var mapLoaded by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(context.hasLocationPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(context.hasNotificationPermission()) }
    var overlayPermissionGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var permissionPromptShownThisVisit by remember { mutableStateOf(false) }
    var permissionFlowInProgress by remember { mutableStateOf(false) }
    val currentPermissionFlowInProgress by rememberUpdatedState(permissionFlowInProgress)
    var followLocation by remember { mutableStateOf(true) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var trackFocusRequest by remember { mutableIntStateOf(0) }
    var showHistory by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var pendingFileDelete by remember { mutableStateOf<GpxSavedFile?>(null) }
    var topographicMap by remember {
        mutableStateOf(context.getSharedPreferences("map", Context.MODE_PRIVATE).getBoolean("topographic", false))
    }
    var pendingDelete by remember { mutableStateOf<TrackSummary?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("") }
    var exportNameIsDefault by remember { mutableStateOf(false) }
    var exportPlacePrefix by remember { mutableStateOf("") }
    var exportPlacePrefixEdited by remember { mutableStateOf(false) }
    var pendingClearAfterSaveAs by rememberSaveable { mutableStateOf(false) }
    var pendingExportTrackId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingExportPlaceName by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(showExportDialog, uiState.placeSuggestionTrackId, uiState.suggestedPlacePrefix) {
        if (showExportDialog && !exportPlacePrefixEdited &&
            uiState.placeSuggestionTrackId == uiState.recording.id
        ) {
            uiState.suggestedPlacePrefix?.let { exportPlacePrefix = it }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        val expectedTrackId = pendingExportTrackId
        pendingExportTrackId = null
        val clearAfterSave = pendingClearAfterSaveAs
        val placeName = pendingExportPlaceName
        pendingClearAfterSaveAs = false
        pendingExportPlaceName = null
        if (uri != null) viewModel.exportCurrentTrack(uri, clearAfterSave, placeName, expectedTrackId)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) {
        showHistory = false
        showFiles = false
        followLocation = false
        trackFocusRequest += 1
        viewModel.importGpx(uri)
    } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionGranted = context.hasLocationPermission() ||
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionDenied = !permissionGranted
        permissionFlowInProgress = false
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayPermissionGranted = Settings.canDrawOverlays(context)
        permissionFlowInProgress = false
        if (overlayPermissionGranted && uiState.recording.mode == RecordingMode.RECORDING) {
            RecordingService.command(context, RecordingService.ACTION_REFRESH_OVERLAY)
        }
    }
    val openOverlaySettings = {
        permissionFlowInProgress = true
        overlayPermissionLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
        )
    }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionGranted = context.hasLocationPermission()
        notificationPermissionGranted = context.hasNotificationPermission()
        overlayPermissionGranted = Settings.canDrawOverlays(context)
        permissionFlowInProgress = false
    }
    val openAppSettings = {
        permissionFlowInProgress = true
        appSettingsLauncher.launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionGranted = context.hasNotificationPermission()
        permissionFlowInProgress = false
        viewModel.startRecording()
    }
    val unifiedNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionGranted = context.hasNotificationPermission()
        permissionFlowInProgress = false
    }
    val startNewRecording = {
        if (!context.hasNotificationPermission()) {
            permissionFlowInProgress = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startRecording()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted || !notificationPermissionGranted || !overlayPermissionGranted) {
            showPermissionsDialog = true
            permissionPromptShownThisVisit = true
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = context.hasLocationPermission()
                notificationPermissionGranted = context.hasNotificationPermission()
                overlayPermissionGranted = Settings.canDrawOverlays(context)
                if (permissionGranted && notificationPermissionGranted && overlayPermissionGranted) {
                    showPermissionsDialog = false
                } else if (!permissionPromptShownThisVisit && !currentPermissionFlowInProgress) {
                    showPermissionsDialog = true
                    permissionPromptShownThisVisit = true
                }
                if (overlayPermissionGranted && currentRecording.mode == RecordingMode.RECORDING) {
                    RecordingService.command(context, RecordingService.ACTION_REFRESH_OVERLAY)
                }
                if (permissionGranted) viewModel.startLocationUpdates()
            } else if (event == Lifecycle.Event.ON_STOP) {
                viewModel.stopLocationUpdates()
                if (!currentPermissionFlowInProgress) permissionPromptShownThisVisit = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            viewModel.startLocationUpdates()
        } else {
            viewModel.stopLocationUpdates()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showPermissionsDialog &&
            (!permissionGranted || !notificationPermissionGranted || !overlayPermissionGranted)) {
            AlertDialog(
                onDismissRequest = { showPermissionsDialog = false },
                title = { Text("应用权限") },
                text = {
                    Column {
                        Text("请检查以下权限。首次打开或以后有权限被关闭时，都可以在这里重新授权。")
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("定位：${if (permissionGranted) "已授权" else "未授权"} · 用于记录 GPS 轨迹")
                        if (!permissionGranted) {
                            TextButton(onClick = {
                                permissionFlowInProgress = true
                                permissionLauncher.launch(arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ))
                            }) { Text("授权定位") }
                        }
                        Text("通知：${if (notificationPermissionGranted) "已授权" else "未授权"} · 用于显示后台录制状态")
                        if (!notificationPermissionGranted) {
                            TextButton(onClick = {
                                permissionFlowInProgress = true
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    unifiedNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }) { Text("授权通知") }
                        }
                        Text("悬浮窗：${if (overlayPermissionGranted) "已授权" else "未授权"} · 用于录制时显示实时数据")
                        if (!overlayPermissionGranted) {
                            TextButton(onClick = openOverlaySettings) { Text("授权悬浮窗") }
                        }
                        TextButton(onClick = openAppSettings) { Text("打开应用设置") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPermissionsDialog = false }) { Text("稍后") }
                },
            )
        }
        OpenStreetMap(
            location = uiState.location,
            isLocationFresh = uiState.isLocationFresh,
            recording = uiState.recording,
            topographic = topographicMap,
            trackFocusRequest = trackFocusRequest,
            followLocation = followLocation,
            recenterRequest = recenterRequest,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { mapLoaded = true },
            onUserMovedMap = { followLocation = false },
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 56.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 5.dp,
            ) {
                MapStatusPanel(
                    values = mapStatusValues(
                        mapLoaded = mapLoaded,
                        permissionGranted = permissionGranted,
                        permissionDenied = permissionDenied,
                        isLocating = uiState.isLocating,
                        location = uiState.location,
                        isLocationFresh = uiState.isLocationFresh,
                        signalLevel = uiState.gpsSignalLevel,
                        locationAgeMillis = uiState.locationAgeMillis,
                        gnssTelemetry = uiState.gnssTelemetry,
                        error = uiState.locationError,
                    ),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(onClick = {
                    topographicMap = !topographicMap
                    context.getSharedPreferences("map", Context.MODE_PRIVATE).edit()
                        .putBoolean("topographic", topographicMap).apply()
                }) { Text(if (topographicMap) "地形图" else "街道图") }
                FilledTonalButton(onClick = {
                    viewModel.refreshSavedGpxFiles()
                    showFiles = true
                }) { Text("GPX 文件") }
            }

        }

        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = { Text("历史轨迹") },
                text = {
                    Column {
                        if (uiState.trackHistory.isEmpty()) {
                            Text("还没有保存的轨迹")
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                items(uiState.trackHistory, key = { it.id }) { summary ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        FilledTonalButton(
                                            onClick = {
                                                showHistory = false
                                                followLocation = false
                                                trackFocusRequest += 1
                                                viewModel.selectTrack(summary.id)
                                            },
                                            modifier = Modifier.weight(1f),
                                            enabled = !uiState.isTrackOperationPending,
                                        ) {
                                            Column {
                                                Text(
                                                    trackSummaryText(summary),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    "地名：${summary.placeName ?: "未设置"}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { pendingDelete = summary },
                                            enabled = !uiState.isTrackOperationPending &&
                                                !(uiState.recording.id == summary.id &&
                                                    (uiState.recording.mode == RecordingMode.RECORDING ||
                                                        controlsLocked)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                            ),
                                        ) { Text("删除") }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "轨迹自动保存在应用内；GPX 默认导出到 ${DefaultGpxExporter.DISPLAY_DIRECTORY}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHistory = false }) { Text("关闭") }
                },
                dismissButton = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                        enabled = uiState.recording.mode != RecordingMode.RECORDING && !uiState.isTrackOperationPending,
                    ) { Text(if (uiState.isImporting) "导入中…" else "导入 GPX") }
                },
            )
        }

        if (showFiles) {
            AlertDialog(
                onDismissRequest = { showFiles = false },
                title = { Text("GPX 文件") },
                text = {
                    Column {
                        Text("默认导出目录：${DefaultGpxExporter.DISPLAY_DIRECTORY}。已浏览的地图区域会缓存供离线查看；未浏览区域需要网络。",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        if (uiState.savedGpxFiles.isEmpty()) Text("默认目录中没有 GPX 文件")
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(uiState.savedGpxFiles, key = { it.uri.toString() }) { file ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(file.name, modifier = Modifier.weight(1f), maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/gpx+xml"
                                            putExtra(Intent.EXTRA_STREAM, file.uri)
                                            clipData = ClipData.newRawUri(file.name, file.uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching { context.startActivity(Intent.createChooser(send, "分享 GPX")) }
                                            .onFailure { Toast.makeText(context, "无法打开分享应用", Toast.LENGTH_SHORT).show() }
                                    }) { Text("分享") }
                                    TextButton(onClick = { pendingFileDelete = file }) { Text("删除") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showFiles = false }) { Text("关闭") } },
                dismissButton = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                        enabled = uiState.recording.mode != RecordingMode.RECORDING && !uiState.isTrackOperationPending,
                    ) { Text("导入 GPX") }
                },
            )
        }

        pendingFileDelete?.let { file ->
            AlertDialog(
                onDismissRequest = { pendingFileDelete = null },
                title = { Text("删除 GPX 文件？") },
                text = { Text("${file.name}\n历史轨迹仍会保留。") },
                confirmButton = { TextButton(onClick = {
                    pendingFileDelete = null
                    viewModel.deleteSavedGpxFile(file)
                }) { Text("删除文件") } },
                dismissButton = { TextButton(onClick = { pendingFileDelete = null }) { Text("取消") } },
            )
        }

        pendingDelete?.let { summary ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除这条轨迹？") },
                text = {
                    Text("${trackSummaryText(summary)}\n\n删除后无法恢复。已导出的 GPX 文件不会删除。")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingDelete = null
                            viewModel.deleteTrack(summary.id)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("确认删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }

        uiState.storageMessage?.let { message ->
            AlertDialog(
                onDismissRequest = viewModel::clearStorageMessage,
                title = { Text("轨迹存储") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = viewModel::clearStorageMessage) { Text("确定") }
                },
                dismissButton = if (uiState.canOpenDefaultExportFolder) {
                    {
                        TextButton(onClick = {
                            if (openDefaultGpxFolder(context)) {
                                viewModel.clearStorageMessage()
                            } else {
                                Toast.makeText(context, "无法打开保存文件夹", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("打开保存文件夹") }
                    }
                } else null,
            )
        }

        if (showExportDialog) {
            val fileName = GpxFileNames.withPlacePrefix(exportPlacePrefix, exportName)
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("轨迹存储") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = exportPlacePrefix,
                            onValueChange = {
                                exportPlacePrefix = it
                                exportPlacePrefixEdited = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("地名前缀（可选）") },
                            placeholder = { Text("例如 bristol") },
                            singleLine = true,
                            isError = !GpxFileNames.isValidPlacePrefix(exportPlacePrefix),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                uiState.isLookingUpPlacePrefix -> "正在查询轨迹起点的市级地名…"
                                uiState.placeLookupMessage != null -> requireNotNull(uiState.placeLookupMessage)
                                else -> "可修改查询得到的地名前缀。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "地名来自 © OpenStreetMap contributors（Nominatim）；查询会发送轨迹起点坐标。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = exportName,
                            onValueChange = {
                                exportName = it
                                exportNameIsDefault = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focus ->
                                    if (focus.isFocused && exportNameIsDefault) {
                                        exportName = ""
                                        exportNameIsDefault = false
                                    }
                                },
                            label = { Text("GPX 文件名") },
                            singleLine = true,
                            isError = GpxFileNames.normalize(exportName) == null,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (fileName == null) "文件名无效或过长" else "最终文件名：$fileName",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "默认保存到 ${DefaultGpxExporter.DISPLAY_DIRECTORY}。" +
                                "保存并清空后仍可在历史轨迹中查看。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                showExportDialog = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    viewModel.exportCurrentTrackToDefaultDirectory(
                                        requireNotNull(fileName),
                                        placeName = exportPlacePrefix,
                                    )
                                } else {
                                    pendingClearAfterSaveAs = false
                                    pendingExportPlaceName = exportPlacePrefix
                                    pendingExportTrackId = uiState.recording.id
                                    exportLauncher.launch(requireNotNull(fileName))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = fileName != null && !uiState.isTrackOperationPending,
                        ) { Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "保存到默认目录" else "保存（选择位置）") }
                        FilledTonalButton(
                            onClick = {
                                showExportDialog = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    viewModel.exportCurrentTrackToDefaultDirectory(
                                        requireNotNull(fileName),
                                        clearAfterSave = true,
                                        placeName = exportPlacePrefix,
                                    )
                                } else {
                                    pendingClearAfterSaveAs = true
                                    pendingExportPlaceName = exportPlacePrefix
                                    pendingExportTrackId = uiState.recording.id
                                    exportLauncher.launch(requireNotNull(fileName))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = fileName != null && !uiState.isTrackOperationPending && !controlsLocked &&
                                uiState.recording.mode != RecordingMode.RECORDING,
                        ) { Text("保存并清空当前录制") }
                        OutlinedButton(
                            onClick = {
                                showExportDialog = false
                                pendingClearAfterSaveAs = false
                                pendingExportPlaceName = exportPlacePrefix
                                pendingExportTrackId = uiState.recording.id
                                exportLauncher.launch(requireNotNull(fileName))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = fileName != null && !uiState.isTrackOperationPending,
                        ) { Text("另存为…") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showExportDialog = false }) { Text("取消") }
                },
            )
        }

        if (!permissionGranted) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 54.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FilledTonalButton(onClick = {
                    viewModel.refreshHistory()
                    showHistory = true
                }) { Text("历史轨迹") }
                Button(onClick = {
                    permissionDenied = false
                    permissionFlowInProgress = true
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }) { Text(if (permissionDenied) "重新授权定位" else "启用当前位置") }
                TextButton(onClick = { showPermissionsDialog = true }) { Text("查看全部权限") }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!notificationPermissionGranted || !overlayPermissionGranted) {
                    TextButton(onClick = { showPermissionsDialog = true }) { Text("查看全部权限") }
                }
                if (uiState.recording.mode != RecordingMode.IDLE) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            text = recordingStatusText(uiState.recording, uiState.recordingElapsedMillis),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (controlsLocked) {
                        Text(
                            text = "暂停和结束已锁定 · 连点锁图标 3 次解锁（${controlLock?.tapCount ?: 0}/3）",
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    } else if (controlLock?.isVisible == true &&
                        uiState.recording.mode in listOf(RecordingMode.RECORDING, RecordingMode.PAUSED)
                    ) {
                        Text(
                            text = "已解锁；15 秒无操作后自动重新锁定",
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        viewModel.refreshHistory()
                        showHistory = true
                    }) { Text("历史轨迹") }
                    if (uiState.recording.id != null) {
                        FilledTonalButton(onClick = {
                            exportName = GpxFileNames.defaultName(uiState.recording)
                            exportNameIsDefault = true
                            exportPlacePrefix = ""
                            exportPlacePrefixEdited = false
                            showExportDialog = true
                            viewModel.suggestExportPlacePrefix()
                        }, enabled = !uiState.isTrackOperationPending) { Text("导出 GPX") }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (controlLock?.isVisible == true &&
                        uiState.recording.mode in listOf(RecordingMode.RECORDING, RecordingMode.PAUSED)
                    ) {
                        IconButton(
                            onClick = viewModel::tapRecordingLock,
                            enabled = controlsLocked,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (controlsLocked) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.primaryContainer,
                                    CircleShape,
                                ),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (controlsLocked) R.drawable.ic_recording_locked
                                    else R.drawable.ic_recording_unlocked,
                                ),
                                contentDescription = if (controlsLocked) "录制控制已锁定，连点三次解锁"
                                    else "录制控制已解锁",
                                tint = if (controlsLocked) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    when (uiState.recording.mode) {
                        RecordingMode.IDLE -> Button(
                            onClick = startNewRecording,
                            enabled = !uiState.isTrackOperationPending,
                        ) { Text(if (uiState.isLoadingTracks) "读取轨迹中…" else "开始录制") }
                        RecordingMode.RECORDING -> {
                            if (!overlayPermissionGranted) {
                                OutlinedButton(onClick = openOverlaySettings) { Text("开启悬浮窗") }
                            }
                            Button(onClick = viewModel::pauseRecording, enabled = !controlsLocked && !uiState.isTrackOperationPending) {
                                Text("暂停")
                            }
                            Button(
                                onClick = viewModel::finishRecording,
                                enabled = !controlsLocked && !uiState.isTrackOperationPending,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("结束") }
                        }
                        RecordingMode.PAUSED -> {
                            Button(onClick = viewModel::resumeRecording, enabled = !uiState.isTrackOperationPending) { Text("继续") }
                            Button(
                                onClick = viewModel::finishRecording,
                                enabled = !controlsLocked && !uiState.isTrackOperationPending,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("结束") }
                        }
                        RecordingMode.FINISHED -> {
                            Button(onClick = viewModel::resumeRecording, enabled = !uiState.isTrackOperationPending) { Text("继续录制") }
                            Button(onClick = startNewRecording, enabled = !uiState.isTrackOperationPending) { Text("新轨迹") }
                        }
                    }
                    FloatingActionButton(
                        onClick = {
                            followLocation = true
                            recenterRequest += 1
                        },
                        containerColor = if (followLocation) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        contentColor = if (followLocation) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ) {
                        Text(text = "◎", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }

        Text(
            text = if (topographicMap) "© OpenStreetMap, SRTM · © OpenTopoMap (CC-BY-SA)" else "© OpenStreetMap contributors",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(8.dp)
                .background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(4.dp))
                .clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            (if (topographicMap) "https://opentopomap.org" else OpenStreetMapCopyrightUrl).toUri()),
                    )
                }
                .padding(horizontal = 7.dp, vertical = 4.dp),
            color = Color(0xFF1A1A1A),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun OpenStreetMap(
    location: LocationSample?,
    isLocationFresh: Boolean,
    recording: TrackRecording,
    topographic: Boolean,
    trackFocusRequest: Int,
    followLocation: Boolean,
    recenterRequest: Int,
    modifier: Modifier = Modifier,
    onMapReady: () -> Unit,
    onUserMovedMap: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnMapReady by rememberUpdatedState(onMapReady)
    val currentOnUserMovedMap by rememberUpdatedState(onUserMovedMap)
    val currentRecordingForStyle by rememberUpdatedState(recording)
    val currentLocationForStyle by rememberUpdatedState(location)
    val currentFreshForStyle by rememberUpdatedState(isLocationFresh)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lastCenteredTimestamp by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var lastRecenterRequest by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var lastRenderedPointCount by remember { mutableIntStateOf(-1) }
    var lastRenderedTrackId by remember { mutableLongStateOf(-1L) }
    var lastFocusedTrackId by remember { mutableLongStateOf(-1L) }
    var lastTrackFocusRequest by remember { mutableIntStateOf(Int.MIN_VALUE) }

    val mapView = remember {
        MapView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            onCreate(Bundle())
            getMapAsync { readyMap ->
                readyMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(51.5074, -0.1278)).zoom(11.0).build()
                readyMap.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(detector: MoveGestureDetector) { currentOnUserMovedMap() }
                    override fun onMove(detector: MoveGestureDetector) = Unit
                    override fun onMoveEnd(detector: MoveGestureDetector) = Unit
                })
                map = readyMap
            }
        }
    }

    LaunchedEffect(map, topographic) {
        map?.setStyle(Style.Builder().fromJson(mapStyle(topographic))) { style ->
            style.installLocationLayers()
            style.updateTrack(currentRecordingForStyle)
            currentLocationForStyle?.let { style.updateLocation(it, currentFreshForStyle) }
            lastRenderedTrackId = currentRecordingForStyle.id ?: -1L
            lastRenderedPointCount = currentRecordingForStyle.pointCount
            currentOnMapReady()
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { mapView },
        update = {
            val readyMap = map ?: return@AndroidView
            if (lastRenderedTrackId != (recording.id ?: -1L) ||
                lastRenderedPointCount != recording.pointCount
            ) {
                readyMap.style?.updateTrack(recording)
                lastRenderedTrackId = recording.id ?: -1L
                lastRenderedPointCount = recording.pointCount
            }
            val trackId = recording.id
            if (trackId != null && recording.pointCount > 0 &&
                (trackId != lastFocusedTrackId || trackFocusRequest != lastTrackFocusRequest)
            ) {
                readyMap.focusTrack(recording)
                lastFocusedTrackId = trackId
                lastTrackFocusRequest = trackFocusRequest
            }
            val readyLocation = location ?: return@AndroidView
            readyMap.style?.updateLocation(readyLocation, isLocationFresh)

            val locationChanged = lastCenteredTimestamp != readyLocation.timestampMillis
            val recenterRequested = lastRecenterRequest != recenterRequest
            if (isLocationFresh && followLocation && (locationChanged || recenterRequested)) {
                lastCenteredTimestamp = readyLocation.timestampMillis
                lastRecenterRequest = recenterRequest
                readyMap.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(readyLocation.latitude, readyLocation.longitude))
                            .zoom(maxOf(readyMap.cameraPosition.zoom, 16.0))
                            .build(),
                    ),
                    700,
                )
            }
        },
        modifier = modifier,
    )
}

private fun MapLibreMap.focusTrack(recording: TrackRecording) {
    val points = recording.segments.flatten()
    if (points.isEmpty()) return
    if (points.size == 1) {
        animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(points[0].latitude, points[0].longitude),
                16.0,
            ),
            700,
        )
        return
    }
    val north = points.maxOf { it.latitude }
    val south = points.minOf { it.latitude }
    val east = points.maxOf { it.longitude }
    val west = points.minOf { it.longitude }
    if (north == south && east == west) {
        animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(north, east), 16.0), 700)
    } else {
        animateCamera(
            CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(north, east, south, west), 100),
            700,
        )
    }
}

private fun Style.installLocationLayers() {
    addSource(GeoJsonSource(TrackSourceId, FeatureCollection.fromFeatures(emptyArray())))
    addLayer(
        LineLayer(TrackLayerId, TrackSourceId).withProperties(
            lineColor("#D32F2F"),
            lineWidth(5f),
        ),
    )

    addSource(GeoJsonSource(AccuracySourceId, FeatureCollection.fromFeatures(emptyArray())))
    addLayer(
        FillLayer(AccuracyLayerId, AccuracySourceId).withProperties(
            fillColor("#1976D2"),
            fillOpacity(0.16f),
        ),
    )

    addSource(GeoJsonSource(LocationSourceId, FeatureCollection.fromFeatures(emptyArray())))
    addLayer(
        CircleLayer(LocationLayerId, LocationSourceId).withProperties(
            circleRadius(8f),
            circleColor("#1976D2"),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(3f),
        ),
    )
}

private fun Style.updateTrack(recording: TrackRecording) {
    val features = recording.segments
        .filter { it.size >= 2 }
        .map { segment ->
            Feature.fromGeometry(
                LineString.fromLngLats(
                    segment.map { Point.fromLngLat(it.longitude, it.latitude) },
                ),
            )
        }
    getSourceAs<GeoJsonSource>(TrackSourceId)?.setGeoJson(
        FeatureCollection.fromFeatures(features),
    )
}

private fun Style.updateLocation(location: LocationSample, isFresh: Boolean) {
    getSourceAs<GeoJsonSource>(LocationSourceId)?.setGeoJson(
        Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)),
    )
    getLayerAs<CircleLayer>(LocationLayerId)?.setProperties(
        circleColor(if (isFresh) "#1976D2" else "#757575"),
    )
    val accuracySource = getSourceAs<GeoJsonSource>(AccuracySourceId)
    if (isFresh) {
        accuracySource?.setGeoJson(Feature.fromGeometry(accuracyPolygon(location)))
    } else {
        accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }
}

private fun accuracyPolygon(location: LocationSample): Polygon {
    val latitudeRadians = Math.toRadians(location.latitude)
    val longitudeRadians = Math.toRadians(location.longitude)
    val angularDistance = location.accuracyMeters.coerceAtLeast(1f) / EarthRadiusMeters
    val coordinates = (0..64).map { index ->
        val bearing = 2.0 * Math.PI * index / 64.0
        val latitude = asin(
            sin(latitudeRadians) * cos(angularDistance) +
                cos(latitudeRadians) * sin(angularDistance) * cos(bearing),
        )
        val longitude = longitudeRadians + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitudeRadians),
            cos(angularDistance) - sin(latitudeRadians) * sin(latitude),
        )
        Point.fromLngLat(Math.toDegrees(longitude), Math.toDegrees(latitude))
    }
    return Polygon.fromLngLats(listOf(coordinates))
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private data class MapStatusValues(
    val signal: String,
    val satellites: String,
    val cn0: String,
    val position: String,
    val accuracy: String,
    val speed: String,
    val altitude: String,
    val age: String,
)

@Composable
private fun MapStatusPanel(values: MapStatusValues) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusCell("GPS", values.signal, 1.2f)
            StatusCell("卫星", values.satellites, 1f)
            StatusCell("信噪比", values.cn0, 1.1f)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusCell("位置", values.position, 2.2f)
            StatusCell("精度", values.accuracy, 1f)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusCell("车速", values.speed, 1.1f)
            StatusCell("海拔", values.altitude, 1f)
            StatusCell("更新", values.age, 1.1f)
        }
    }
}

@Composable
private fun RowScope.StatusCell(label: String, value: String, weight: Float) {
    Column(modifier = Modifier.weight(weight).padding(end = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun mapStatusValues(
    mapLoaded: Boolean,
    permissionGranted: Boolean,
    permissionDenied: Boolean,
    isLocating: Boolean,
    location: LocationSample?,
    isLocationFresh: Boolean,
    signalLevel: GpsSignalLevel,
    locationAgeMillis: Long?,
    gnssTelemetry: GnssTelemetry?,
    error: String?,
): MapStatusValues {
    val signal = when {
        !mapLoaded -> "地图加载中"
        !permissionGranted && permissionDenied -> "未授权"
        !permissionGranted -> "未启用"
        error != null -> error
        else -> when (signalLevel) {
            GpsSignalLevel.SEARCHING -> "搜索中"
            GpsSignalLevel.STRONG -> "强"
            GpsSignalLevel.MODERATE -> "一般"
            GpsSignalLevel.WEAK -> "较弱"
            GpsSignalLevel.LOST -> "已丢失"
            GpsSignalLevel.DISABLED -> "已关闭"
        }
    }
    val validLocation = location?.takeUnless { isLocating }
    val position = validLocation?.let {
        val prefix = if (isLocationFresh) "" else "上次 "
        prefix + String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude)
    } ?: "--"
    return MapStatusValues(
        signal = signal,
        satellites = gnssTelemetry?.let { "${it.usedSatellites}/${it.visibleSatellites} 颗" } ?: "--",
        cn0 = gnssTelemetry?.averageCn0DbHz?.let {
            String.format(Locale.US, "%.0f dB-Hz", it)
        } ?: "--",
        position = position,
        accuracy = validLocation?.takeIf { isLocationFresh }?.let {
            String.format(Locale.US, "±%.0f m", it.accuracyMeters)
        } ?: "--",
        speed = speedText(validLocation, isLocationFresh),
        altitude = altitudeText(validLocation, isLocationFresh),
        age = validLocation?.let { locationAgeText(locationAgeMillis) } ?: "--",
    )
}

private fun locationAgeText(ageMillis: Long?): String = when {
    ageMillis == null -> "时间未知"
    ageMillis < 2_000L -> "刚刚"
    ageMillis < 60_000L -> "${ageMillis / 1_000L} 秒前"
    else -> "${ageMillis / 60_000L} 分钟前"
}

private fun recordingStatusText(recording: TrackRecording, elapsedMillis: Long): String {
    val status = when (recording.mode) {
        RecordingMode.IDLE -> "准备录制"
        RecordingMode.RECORDING -> "正在录制"
        RecordingMode.PAUSED -> "已暂停"
        RecordingMode.FINISHED -> "已结束"
    }
    val totalSeconds = elapsedMillis / 1_000L
    val duration = String.format(Locale.US, "%02d:%02d:%02d", totalSeconds / 3_600L,
        totalSeconds / 60L % 60L, totalSeconds % 60L)
    val distance = if (recording.distanceMeters < 1_000.0) {
        String.format(Locale.US, "%.0f m", recording.distanceMeters)
    } else {
        String.format(Locale.US, "%.2f km", recording.distanceMeters / 1_000.0)
    }
    return "$status · $duration · $distance · ${recording.pointCount} 点\n已自动保存到本机"
}

private fun trackSummaryText(summary: TrackSummary): String {
    val date = SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(summary.startedAtMillis))
    val status = when (summary.mode) {
        RecordingMode.RECORDING -> "录制中"
        RecordingMode.PAUSED -> "已暂停"
        RecordingMode.FINISHED -> "已结束"
        RecordingMode.IDLE -> "空轨迹"
    }
    val distance = if (summary.distanceMeters < 1_000.0) {
        String.format(Locale.US, "%.0f m", summary.distanceMeters)
    } else {
        String.format(Locale.US, "%.2f km", summary.distanceMeters / 1_000.0)
    }
    return "$date\n$status · $distance"
}

private fun openDefaultGpxFolder(context: Context): Boolean {
    val folderUri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${DefaultGpxExporter.DISPLAY_DIRECTORY}",
    )
    val browseIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri)
        }
    }
    return runCatching { context.startActivity(browseIntent) }.isSuccess
}

private const val EarthRadiusMeters = 6_371_000.0
