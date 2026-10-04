// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.opengpxtracker.android.location.AndroidLocationClient
import org.opengpxtracker.android.location.GnssTelemetry
import org.opengpxtracker.android.location.GpsSignalLevel
import org.opengpxtracker.android.location.LocationEvent
import org.opengpxtracker.android.location.LocationSample
import org.opengpxtracker.android.location.LOCATION_STALE_AFTER_MILLIS
import org.opengpxtracker.android.location.OsmPlacePrefix
import org.opengpxtracker.android.location.assessGpsSignal
import org.opengpxtracker.android.track.TrackRecording
import org.opengpxtracker.android.track.TrackStore
import org.opengpxtracker.android.track.TrackSummary
import org.opengpxtracker.android.track.GpxWriter
import org.opengpxtracker.android.track.DefaultGpxExporter
import org.opengpxtracker.android.track.GpxFileNames
import org.opengpxtracker.android.track.RecordingMode
import org.opengpxtracker.android.track.RecordingService
import org.opengpxtracker.android.track.RecordingSession
import org.opengpxtracker.android.track.RecordingControlLockState
import org.opengpxtracker.android.track.GpxReader
import org.opengpxtracker.android.track.GpxFileRepository
import org.opengpxtracker.android.track.GpxSavedFile

data class MapUiState(
    val location: LocationSample? = null,
    val isLocating: Boolean = false,
    val locationError: String? = null,
    val gpsSignalLevel: GpsSignalLevel = GpsSignalLevel.SEARCHING,
    val locationAgeMillis: Long? = null,
    val isLocationFresh: Boolean = false,
    val gnssTelemetry: GnssTelemetry? = null,
    val recording: TrackRecording = TrackRecording(),
    val recordingElapsedMillis: Long = 0L,
    val controlLockState: RecordingControlLockState = RecordingControlLockState(),
    val trackHistory: List<TrackSummary> = emptyList(),
    val savedGpxFiles: List<GpxSavedFile> = emptyList(),
    val isImporting: Boolean = false,
    val isSendingRecordingCommand: Boolean = false,
    val isLoadingTracks: Boolean = true,
    val deletingTrackId: Long? = null,
    val storageMessage: String? = null,
    val canOpenDefaultExportFolder: Boolean = false,
    val isExporting: Boolean = false,
    val placeSuggestionTrackId: Long? = null,
    val suggestedPlacePrefix: String? = null,
    val isLookingUpPlacePrefix: Boolean = false,
    val placeLookupMessage: String? = null,
) {
    val isTrackOperationPending: Boolean
        get() = isLoadingTracks || isImporting || isExporting ||
            deletingTrackId != null || isSendingRecordingCommand
}

class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val locationClient = AndroidLocationClient(application)
    private val trackStore = TrackStore(application)
    private val gpxFiles = GpxFileRepository(application)
    private val osmPlacePrefix = OsmPlacePrefix(application)
    private val mutableState = MutableStateFlow(MapUiState())
    private var locationJob: Job? = null
    private var freshnessJob: Job? = null
    private var fusedLocationAvailable = true
    private var gpsProviderEnabled = true
    private var gnssTelemetry: GnssTelemetry? = null

    val state: StateFlow<MapUiState> = mutableState.asStateFlow()

    init {
        RecordingSession.controlLockState.onEach { controlLockState ->
            mutableState.value = mutableState.value.copy(controlLockState = controlLockState)
        }.launchIn(viewModelScope)
        RecordingSession.recording.onEach { recording ->
            if (recording != null) {
                val previous = mutableState.value.recording
                mutableState.value = mutableState.value.copy(
                    recording = recording,
                    recordingElapsedMillis = recording.elapsedAt(SystemClock.elapsedRealtime()),
                    isSendingRecordingCommand = false,
                )
                if (previous.id != recording.id || previous.mode != recording.mode) refreshHistory()
            }
        }.launchIn(viewModelScope)
        RecordingSession.error.onEach { error ->
            if (error != null) {
                mutableState.value = mutableState.value.copy(storageMessage = error, isSendingRecordingCommand = false)
                RecordingSession.clearError()
            }
        }.launchIn(viewModelScope)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (RecordingSession.recording.value?.mode != RecordingMode.RECORDING) {
                        trackStore.recoverInterruptedRecording()
                    }
                    val history = trackStore.list()
                    history to history.firstOrNull()?.let { trackStore.load(it.id) }
                }
            }.onSuccess { (history, latest) ->
                if (RecordingSession.recording.value == null && latest?.mode == RecordingMode.PAUSED) {
                    latest.id?.let {
                        RecordingSession.observeTrackAge(
                            it, (System.currentTimeMillis() - latest.startedAtMillis).coerceAtLeast(0L),
                        )
                    }
                }
                mutableState.value = mutableState.value.copy(
                    recording = RecordingSession.recording.value ?: latest ?: TrackRecording(),
                    recordingElapsedMillis = RecordingSession.recording.value
                        ?.elapsedAt(SystemClock.elapsedRealtime()) ?: latest?.elapsedMillis ?: 0L,
                    trackHistory = history,
                    isLoadingTracks = false,
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    isLoadingTracks = false,
                    storageMessage = "读取轨迹失败：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    fun startLocationUpdates() {
        if (locationJob != null) return

        mutableState.value = mutableState.value.copy(
            isLocating = true,
            locationError = null,
        )
        locationJob = viewModelScope.launch {
            while (isActive) {
                try {
                    locationClient.updates().collect { event ->
                        when (event) {
                            is LocationEvent.Sample -> {
                                mutableState.value = mutableState.value.copy(
                                    location = event.location,
                                    isLocating = false,
                                    locationError = null,
                                )
                                refreshSignalState()
                            }
                            is LocationEvent.Availability -> {
                                fusedLocationAvailable = event.isAvailable
                            }
                            is LocationEvent.ProviderEnabled -> {
                                gpsProviderEnabled = event.isEnabled
                            }
                            is LocationEvent.Satellites -> {
                                gnssTelemetry = event.telemetry
                            }
                        }
                        if (event !is LocationEvent.Sample) refreshSignalState()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableState.value = mutableState.value.copy(
                        isLocating = false,
                        locationError = error.message ?: "无法获取当前位置",
                    )
                }
                delay(LOCATION_RETRY_INTERVAL_MILLIS)
            }
        }

        freshnessJob = viewModelScope.launch {
            while (isActive) {
                refreshSignalState()
                val recording = mutableState.value.recording
                val elapsed = recording.elapsedAt(SystemClock.elapsedRealtime())
                mutableState.value = mutableState.value.copy(
                    recordingElapsedMillis = elapsed,
                )
                delay(FRESHNESS_REFRESH_MILLIS)
            }
        }
    }

    fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
        freshnessJob?.cancel()
        freshnessJob = null
        mutableState.value = mutableState.value.copy(
            isLocating = false,
            isLocationFresh = false,
        )
    }

    fun startRecording() {
        if (mutableState.value.isTrackOperationPending) return
        sendRecordingCommand(RecordingService.ACTION_START)
    }

    fun pauseRecording() = sendRecordingCommand(RecordingService.ACTION_PAUSE)

    fun resumeRecording() = sendRecordingCommand(RecordingService.ACTION_RESUME)

    fun finishRecording() = sendRecordingCommand(RecordingService.ACTION_FINISH)

    fun tapRecordingLock() = sendRecordingCommand(RecordingService.ACTION_UNLOCK_TAP)

    private fun sendRecordingCommand(action: String) {
        if (mutableState.value.isTrackOperationPending) return
        val state = mutableState.value
        if ((action == RecordingService.ACTION_PAUSE || action == RecordingService.ACTION_FINISH) &&
            state.controlLockState.trackId == state.recording.id &&
            state.controlLockState.isLocked
        ) return
        val waitsForRecording = action != RecordingService.ACTION_UNLOCK_TAP
        if (waitsForRecording) mutableState.value = state.copy(isSendingRecordingCommand = true)
        runCatching {
            RecordingService.command(getApplication(), action, mutableState.value.recording.id)
        }.onFailure {
            mutableState.value = mutableState.value.copy(isSendingRecordingCommand = false)
            showStorageError("控制录制", it)
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { trackStore.list() } }
                .onSuccess { mutableState.value = mutableState.value.copy(trackHistory = it) }
                .onFailure { showStorageError("读取历史轨迹", it) }
        }
    }

    fun importGpx(uri: Uri) {
        if (mutableState.value.isTrackOperationPending) return
        if (mutableState.value.recording.mode == RecordingMode.RECORDING) {
            mutableState.value = mutableState.value.copy(storageMessage = "请先暂停当前录制，再导入 GPX")
            return
        }
        mutableState.value = mutableState.value.copy(isImporting = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri))
                    val parsed = input.buffered().use { GpxReader.read(it) }
                    val id = trackStore.import(parsed)
                    requireNotNull(trackStore.load(id)) to trackStore.list()
                }
            }.onSuccess { (recording, history) ->
                mutableState.value = mutableState.value.copy(
                    recording = recording,
                    recordingElapsedMillis = recording.elapsedMillis,
                    trackHistory = history,
                    isImporting = false,
                    storageMessage = "GPX 已导入历史轨迹；点击「继续录制」可接着记录",
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(isImporting = false)
                showStorageError("导入 GPX", error)
            }
        }
    }

    fun refreshSavedGpxFiles() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { gpxFiles.list() } }
                .onSuccess { mutableState.value = mutableState.value.copy(savedGpxFiles = it) }
                .onFailure { showStorageError("读取 GPX 文件", it) }
        }
    }

    fun deleteSavedGpxFile(file: GpxSavedFile) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { gpxFiles.delete(file) } }
                .onSuccess {
                    if (it) {
                        refreshSavedGpxFiles()
                        mutableState.value = mutableState.value.copy(storageMessage = "GPX 文件已删除；历史轨迹仍保留")
                    } else showStorageError("删除 GPX 文件", IllegalStateException("文件不存在或没有权限"))
                }
                .onFailure { showStorageError("删除 GPX 文件", it) }
        }
    }

    fun selectTrack(id: Long) {
        if (mutableState.value.isTrackOperationPending) return
        if (mutableState.value.recording.mode == RecordingMode.RECORDING) {
            mutableState.value = mutableState.value.copy(storageMessage = "请先暂停当前录制，再打开历史轨迹")
            return
        }
        mutableState.value = mutableState.value.copy(isLoadingTracks = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { trackStore.load(id) } }
                .onSuccess { recording ->
                    if (recording != null) {
                        if (recording.mode == RecordingMode.PAUSED) {
                            recording.id?.let {
                                RecordingSession.observeTrackAge(
                                    it, (System.currentTimeMillis() - recording.startedAtMillis).coerceAtLeast(0L),
                                )
                            }
                        }
                        mutableState.value = mutableState.value.copy(
                            recording = recording,
                            recordingElapsedMillis = recording.elapsedMillis,
                            isLoadingTracks = false,
                        )
                    } else {
                        mutableState.value = mutableState.value.copy(isLoadingTracks = false)
                    }
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(isLoadingTracks = false)
                    showStorageError("打开轨迹", it)
                }
        }
    }

    fun deleteTrack(id: Long) {
        val current = mutableState.value
        if (current.isTrackOperationPending) return
        if (current.recording.id == id && current.recording.mode == RecordingMode.RECORDING) {
            mutableState.value = current.copy(storageMessage = "请先结束或暂停当前录制，再删除轨迹")
            return
        }
        if (current.recording.id == id && current.controlLockState.trackId == id &&
            current.controlLockState.isLocked
        ) {
            mutableState.value = current.copy(storageMessage = "请先解锁当前轨迹，再删除")
            return
        }
        mutableState.value = current.copy(deletingTrackId = id)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    check(trackStore.delete(id)) { "轨迹不存在或已删除" }
                    val history = trackStore.list()
                    history to history.firstOrNull()?.let { trackStore.load(it.id) }
                }
            }.onSuccess { (history, latest) ->
                val state = mutableState.value
                val selectedDeleted = state.recording.id == id
                mutableState.value = state.copy(
                    trackHistory = history,
                    recording = if (selectedDeleted) latest ?: TrackRecording() else state.recording,
                    recordingElapsedMillis = if (selectedDeleted) latest?.elapsedMillis ?: 0L
                        else state.recordingElapsedMillis,
                    deletingTrackId = null,
                    storageMessage = null,
                )
                if (selectedDeleted) RecordingSession.clearIfCurrent(id)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(deletingTrackId = null)
                showStorageError("删除轨迹", error)
            }
        }
    }

    fun suggestExportPlacePrefix() {
        val recording = mutableState.value.recording
        val id = recording.id ?: return
        val savedPlaceName = mutableState.value.trackHistory.firstOrNull { it.id == id }?.placeName
        if (!savedPlaceName.isNullOrBlank()) {
            mutableState.value = mutableState.value.copy(
                placeSuggestionTrackId = id,
                suggestedPlacePrefix = savedPlaceName,
                isLookingUpPlacePrefix = false,
                placeLookupMessage = null,
            )
            return
        }
        if (recording.segments.firstOrNull()?.firstOrNull() == null) {
            mutableState.value = mutableState.value.copy(
                placeSuggestionTrackId = id,
                suggestedPlacePrefix = null,
                isLookingUpPlacePrefix = false,
                placeLookupMessage = "轨迹没有定位点，可手动填写前缀",
            )
            return
        }
        if (mutableState.value.placeSuggestionTrackId == id &&
            (mutableState.value.isLookingUpPlacePrefix || mutableState.value.suggestedPlacePrefix != null)
        ) return
        mutableState.value = mutableState.value.copy(
            placeSuggestionTrackId = id,
            suggestedPlacePrefix = null,
            isLookingUpPlacePrefix = true,
            placeLookupMessage = null,
        )
        viewModelScope.launch {
            val result = runCatching { osmPlacePrefix.suggest(recording) }
            if (mutableState.value.placeSuggestionTrackId != id) return@launch
            mutableState.value = mutableState.value.copy(
                suggestedPlacePrefix = result.getOrNull(),
                isLookingUpPlacePrefix = false,
                placeLookupMessage = when {
                    result.isFailure -> "地名查询失败，可手动填写前缀"
                    result.getOrNull() == null -> "未查到市级地名，可手动填写前缀"
                    else -> null
                },
            )
        }
    }

    fun exportCurrentTrackToDefaultDirectory(
        fileName: String,
        clearAfterSave: Boolean = false,
        placeName: String? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mutableState.value = mutableState.value.copy(storageMessage = "请使用「另存为」选择 GPX 保存位置")
            return
        }
        val id = mutableState.value.recording.id ?: return
        if (mutableState.value.isTrackOperationPending) return
        if (clearAfterSave && isCurrentRecordingLocked()) {
            mutableState.value = mutableState.value.copy(storageMessage = "请先解锁当前轨迹，再清空录制")
            return
        }
        val normalizedName = GpxFileNames.normalize(fileName)
        if (normalizedName == null) {
            mutableState.value = mutableState.value.copy(storageMessage = "GPX 文件名无效")
            return
        }
        if (clearAfterSave && mutableState.value.recording.mode == RecordingMode.RECORDING) {
            mutableState.value = mutableState.value.copy(storageMessage = "请先暂停录制，再保存并清空")
            return
        }
        mutableState.value = mutableState.value.copy(isExporting = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val recording = requireNotNull(trackStore.load(id))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        DefaultGpxExporter.export(getApplication(), recording, normalizedName)
                    }
                    recording.pointCount to runCatching { trackStore.savePlaceName(id, placeName) }
                        .getOrDefault(false)
                }
            }.onSuccess { (pointCount, placeSaved) ->
                completeExport(
                    id = id,
                    pointCount = pointCount,
                    clearAfterSave = clearAfterSave,
                    defaultFolder = true,
                    placeSaved = placeSaved,
                )
            }.onFailure {
                mutableState.value = mutableState.value.copy(isExporting = false)
                showStorageError("导出 GPX", it)
            }
        }
    }

    fun exportCurrentTrack(uri: Uri, clearAfterSave: Boolean = false, placeName: String? = null,
        expectedTrackId: Long? = mutableState.value.recording.id) {
        val id = mutableState.value.recording.id ?: return
        if (id != expectedTrackId) {
            mutableState.value = mutableState.value.copy(storageMessage = "当前轨迹已改变，请重新选择导出")
            return
        }
        if (mutableState.value.isTrackOperationPending) return
        if (clearAfterSave && isCurrentRecordingLocked()) {
            mutableState.value = mutableState.value.copy(storageMessage = "请先解锁当前轨迹，再清空录制")
            return
        }
        if (clearAfterSave && mutableState.value.recording.mode == RecordingMode.RECORDING) {
            mutableState.value = mutableState.value.copy(storageMessage = "请先暂停录制，再保存并清空")
            return
        }
        mutableState.value = mutableState.value.copy(isExporting = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val recording = requireNotNull(trackStore.load(id))
                    val output = requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "w"))
                    output.use { GpxWriter.write(recording, it) }
                    recording.pointCount to runCatching { trackStore.savePlaceName(id, placeName) }
                        .getOrDefault(false)
                }
            }.onSuccess { (pointCount, placeSaved) ->
                completeExport(
                    id = id,
                    pointCount = pointCount,
                    clearAfterSave = clearAfterSave,
                    defaultFolder = false,
                    placeSaved = placeSaved,
                )
            }.onFailure {
                mutableState.value = mutableState.value.copy(isExporting = false)
                showStorageError("导出 GPX", it)
            }
        }
    }

    private fun completeExport(
        id: Long,
        pointCount: Int,
        clearAfterSave: Boolean,
        defaultFolder: Boolean,
        placeSaved: Boolean,
    ) {
        var cleared = false
        if (clearAfterSave && mutableState.value.recording.id == id && !isCurrentRecordingLocked()) {
            val current = mutableState.value.recording
            val finished = current.finish(SystemClock.elapsedRealtime())
            if (finished == current || saveRecording(finished)) {
                mutableState.value = mutableState.value.copy(
                    recording = TrackRecording(),
                    recordingElapsedMillis = 0L,
                )
                RecordingSession.clearIfCurrent(id)
                cleared = true
            }
        }
        val location = if (defaultFolder) "到 ${DefaultGpxExporter.DISPLAY_DIRECTORY}" else ""
        val empty = if (pointCount == 0) "（轨迹没有定位点）" else ""
        val clearResult = when {
            !clearAfterSave -> ""
            cleared -> "；当前录制已清空，历史轨迹已保留"
            else -> "；当前录制未清空，请检查保存状态"
        }
        refreshHistory()
        mutableState.value = mutableState.value.copy(
            isExporting = false,
            storageMessage = "GPX 已保存$location$empty$clearResult" +
                if (placeSaved) "" else "；地名未写入历史轨迹",
            canOpenDefaultExportFolder = defaultFolder,
        )
    }

    fun clearStorageMessage() {
        mutableState.value = mutableState.value.copy(
            storageMessage = null,
            canOpenDefaultExportFolder = false,
        )
    }

    private fun saveRecording(recording: TrackRecording): Boolean =
        runCatching {
            val elapsed = recording.elapsedAt(SystemClock.elapsedRealtime())
            trackStore.save(recording, null, elapsed)
        }.onFailure { showStorageError("保存轨迹", it) }.isSuccess

    private fun isCurrentRecordingLocked(): Boolean =
        mutableState.value.controlLockState.let { it.trackId == mutableState.value.recording.id && it.isLocked }

    private fun showStorageError(action: String, error: Throwable) {
        mutableState.value = mutableState.value.copy(
            storageMessage = "$action 失败：${error.message ?: "未知错误"}",
            canOpenDefaultExportFolder = false,
        )
    }

    private fun refreshSignalState() {
        val location = mutableState.value.location
        val ageMillis = location?.let {
            ((SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / NANOS_PER_MILLISECOND)
                .coerceAtLeast(0L)
        }
        val level = assessGpsSignal(
            location = location,
            locationAgeMillis = ageMillis,
            fusedLocationAvailable = fusedLocationAvailable,
            gpsProviderEnabled = gpsProviderEnabled,
            gnssTelemetry = gnssTelemetry,
        )
        val fresh = ageMillis != null &&
            ageMillis <= LOCATION_STALE_AFTER_MILLIS &&
            level != GpsSignalLevel.LOST &&
            level != GpsSignalLevel.DISABLED
        mutableState.value = mutableState.value.copy(
            gpsSignalLevel = level,
            locationAgeMillis = ageMillis,
            isLocationFresh = fresh,
            gnssTelemetry = gnssTelemetry,
        )
    }

    private companion object {
        const val FRESHNESS_REFRESH_MILLIS = 1_000L
        const val LOCATION_RETRY_INTERVAL_MILLIS = 10_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    override fun onCleared() {
        trackStore.close()
        super.onCleared()
    }
}
