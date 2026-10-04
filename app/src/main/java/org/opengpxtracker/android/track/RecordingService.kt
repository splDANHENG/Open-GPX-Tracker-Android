// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Open GPX Tracker Android contributors

package org.opengpxtracker.android.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opengpxtracker.android.MainActivity
import org.opengpxtracker.android.R
import org.opengpxtracker.android.location.AndroidLocationClient
import org.opengpxtracker.android.location.LocationEvent
import org.opengpxtracker.android.location.LocationSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-process stream used by the UI; SQLite remains the durable recording store. */
object RecordingSession {
    private val mutableRecording = MutableStateFlow<TrackRecording?>(null)
    val recording = mutableRecording.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val controlLock = RecordingControlLock()
    private val lockHandler = Handler(Looper.getMainLooper())
    private var relockRunnable: Runnable? = null
    private val mutableControlLockState = MutableStateFlow(controlLock.state)
    val controlLockState = mutableControlLockState.asStateFlow()

    internal fun publish(recording: TrackRecording) { mutableRecording.value = recording }
    internal fun report(error: String) { mutableError.value = error }
    internal fun beginTrack(id: Long) {
        if (controlLock.state.trackId != id) cancelRelock()
        controlLock.beginTrack(id)
        mutableControlLockState.value = controlLock.state
    }
    internal fun observeTrackAge(id: Long, ageMillis: Long): Boolean {
        if (controlLock.state.trackId != id) cancelRelock()
        val previous = controlLock.state
        controlLock.observeTrackAge(id, ageMillis)
        controlLock.expireUnlock(id, SystemClock.elapsedRealtime())
        mutableControlLockState.value = controlLock.state
        return previous != controlLock.state
    }
    internal fun tapLock(id: Long, nowMillis: Long) {
        val wasLocked = controlLock.state.isLocked
        controlLock.tap(id, nowMillis)
        mutableControlLockState.value = controlLock.state
        if (wasLocked && !controlLock.state.isLocked) {
            scheduleRelock(id)
        }
    }
    fun noteInteraction() {
        val id = controlLock.state.trackId ?: return
        if (controlLock.noteInteraction(id, SystemClock.elapsedRealtime())) scheduleRelock(id)
        mutableControlLockState.value = controlLock.state
    }

    private fun scheduleRelock(id: Long) {
        cancelRelock()
        val task = Runnable {
            controlLock.expireUnlock(id, SystemClock.elapsedRealtime())
            mutableControlLockState.value = controlLock.state
        }
        relockRunnable = task
        lockHandler.postDelayed(task, RecordingControlLock.UNLOCK_DURATION_MILLIS)
    }
    fun clearError() { mutableError.value = null }
    fun clearIfCurrent(id: Long) {
        if (mutableRecording.value?.id == id) mutableRecording.value = null
        if (mutableControlLockState.value.trackId == id) {
            cancelRelock()
            controlLock.reset()
            mutableControlLockState.value = controlLock.state
        }
    }

    private fun cancelRelock() {
        relockRunnable?.let(lockHandler::removeCallbacks)
        relockRunnable = null
    }
}

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: TrackStore
    private lateinit var locationClient: AndroidLocationClient
    private lateinit var overlay: RecordingOverlay
    private var recording: TrackRecording? = null
    private var lastLocationSample: LocationSample? = null
    private var locationJob: Job? = null
    private var timerJob: Job? = null
    private var lastElapsedSavedMillis = 0L
    private var lastNotificationDistance = ""
    private var lastNotificationUpdateMillis = 0L
    private var lastNotificationLocked = false

    override fun onCreate() {
        super.onCreate()
        store = TrackStore(this)
        locationClient = AndroidLocationClient(this)
        overlay = RecordingOverlay(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getLongExtra(EXTRA_TRACK_ID, -1L)?.takeIf { it > 0L }
        when (action) {
            ACTION_START, ACTION_RESUME -> {
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification(),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
                    )
                    if (action == ACTION_START) startNewRecording() else resumeRecording(id)
                } catch (error: Exception) {
                    RecordingSession.report("无法开始后台录制：${error.message ?: "未知错误"}")
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                runCatching {
                    loadIfNeeded(id)
                    stopRecording(finish = false)
                }.onFailure { RecordingSession.report("暂停录制失败：${it.message ?: "未知错误"}") }
            }
            ACTION_FINISH -> {
                runCatching {
                    loadIfNeeded(id)
                    stopRecording(finish = true)
                }.onFailure { RecordingSession.report("结束录制失败：${it.message ?: "未知错误"}") }
            }
            ACTION_UNLOCK_TAP -> {
                runCatching {
                    loadIfNeeded(id)
                    val current = recording
                    if (current != null && id != null && id == current.id) {
                        RecordingSession.observeTrackAge(id, current.ageMillis())
                        RecordingSession.tapLock(id, SystemClock.elapsedRealtime())
                        if (current.mode == RecordingMode.RECORDING) updateNotification()
                        else stopSelf()
                    } else stopSelf()
                }.onFailure {
                    RecordingSession.report("解锁录制失败：${it.message ?: "未知错误"}")
                    if (recording?.mode != RecordingMode.RECORDING) stopSelf()
                }
            }
            ACTION_REFRESH_OVERLAY -> {
                recording?.takeIf { it.mode == RecordingMode.RECORDING }?.let(::updateOverlay)
            }
            else -> stopSelf()
        }
        // A killed process is recovered as paused next time the app opens.
        return START_NOT_STICKY
    }

    private fun startNewRecording() {
        if (recording?.mode == RecordingMode.RECORDING) return
        val startedAt = System.currentTimeMillis()
        val id = store.create(startedAt)
        recording = TrackRecording().start(SystemClock.elapsedRealtime(), startedAt).copy(id = id)
        lastLocationSample = null
        RecordingSession.beginTrack(id)
        lastElapsedSavedMillis = 0L
        RecordingSession.publish(requireNotNull(recording))
        startCollecting()
        updateNotification()
        updateOverlay(requireNotNull(recording))
    }

    private fun resumeRecording(id: Long?) {
        if (recording?.id == id && recording?.mode == RecordingMode.RECORDING) return
        if (recording?.mode == RecordingMode.RECORDING) {
            RecordingSession.report("请先暂停当前录制")
            return
        }
        val track = id?.let(store::load)
        if (track == null || track.mode !in setOf(RecordingMode.PAUSED, RecordingMode.FINISHED)) {
            RecordingSession.report("无法继续录制：轨迹已不可用")
            stopSelf()
            return
        }
        recording = track.resume(SystemClock.elapsedRealtime())
        lastLocationSample = null
        RecordingSession.observeTrackAge(id, requireNotNull(recording).ageMillis())
        if (!saveAndPublish(requireNotNull(recording))) return
        startCollecting()
        updateNotification()
        updateOverlay(requireNotNull(recording))
    }

    private fun loadIfNeeded(id: Long?) {
        if (recording?.id != id && id != null) {
            check(recording?.mode != RecordingMode.RECORDING) { "当前录制与操作的轨迹不一致" }
            recording = store.load(id)
        }
    }

    private fun startCollecting() {
        locationJob?.cancel()
        timerJob?.cancel()
        locationJob = scope.launch {
            while (isActive) {
                try {
                    locationClient.updates().collect { event ->
                        val current = recording ?: return@collect
                        if (current.mode != RecordingMode.RECORDING) return@collect
                        when (event) {
                            is LocationEvent.Sample -> {
                                val next = current.add(event.location)
                                if (next.pointCount > current.pointCount) {
                                    lastLocationSample = event.location
                                    saveAndPublish(next, next.segments.last().last())
                                }
                                recording?.takeIf { it.mode == RecordingMode.RECORDING }?.let(::updateOverlay)
                            }
                            else -> Unit
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    RecordingSession.report("后台定位失败，正在重试：${error.message ?: "未知错误"}")
                }
                delay(LOCATION_RETRY_INTERVAL_MILLIS)
            }
        }
        timerJob = scope.launch {
            while (isActive) {
                val current = recording ?: break
                if (current.mode != RecordingMode.RECORDING) break
                val elapsed = current.elapsedAt(SystemClock.elapsedRealtime())
                updateOverlay(current)
                if (current.id?.let { RecordingSession.observeTrackAge(it, current.ageMillis()) } == true ||
                    RecordingSession.controlLockState.value.isLocked != lastNotificationLocked
                ) {
                    updateNotification()
                } else if (notificationDistance(current) != lastNotificationDistance &&
                    SystemClock.elapsedRealtime() - lastNotificationUpdateMillis >= NOTIFICATION_UPDATE_INTERVAL_MILLIS
                ) {
                    updateNotification()
                }
                if (elapsed - lastElapsedSavedMillis >= ELAPSED_SAVE_INTERVAL_MILLIS) {
                    try {
                        current.id?.let { store.saveElapsed(it, elapsed) }
                        lastElapsedSavedMillis = elapsed
                    } catch (error: Exception) {
                        RecordingSession.report("保存录制时长失败：${error.message ?: "未知错误"}")
                        stopRecording(finish = false, enforceControlLock = false)
                        break
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun saveAndPublish(next: TrackRecording, point: TrackPoint? = null): Boolean {
        return try {
            val elapsed = next.elapsedAt(SystemClock.elapsedRealtime())
            store.save(next, point, elapsed)
            lastElapsedSavedMillis = elapsed
            recording = next
            RecordingSession.publish(next)
            true
        } catch (error: Exception) {
            RecordingSession.report("保存轨迹失败：${error.message ?: "未知错误"}")
            // Do not present an unsaved point as durable history after a write failure.
            val persisted = recording
            recording = persisted?.pause(SystemClock.elapsedRealtime())
            recording?.let {
                runCatching { store.save(it, null, it.elapsedMillis) }
                RecordingSession.publish(it)
            }
            stopSelf()
            false
        }
    }

    private fun stopRecording(finish: Boolean, enforceControlLock: Boolean = true) {
        val current = recording
        if (current?.id != null) {
            RecordingSession.observeTrackAge(current.id, current.ageMillis())
            if (enforceControlLock && RecordingSession.controlLockState.value.isLocked) {
                RecordingSession.report("录制控制已锁定，请连点锁图标 3 次解锁")
                if (current.mode != RecordingMode.RECORDING) stopSelf()
                else updateNotification()
                return
            }
        }
        if (current != null && (current.mode == RecordingMode.RECORDING ||
                (finish && current.mode == RecordingMode.PAUSED))) {
            val next = if (finish) current.finish(SystemClock.elapsedRealtime())
                else current.pause(SystemClock.elapsedRealtime())
            saveAndPublish(next)
        }
        locationJob?.cancel()
        timerJob?.cancel()
        locationJob = null
        timerJob = null
        overlay.remove()
        stopSelf()
    }

    override fun onDestroy() {
        overlay.remove()
        val current = recording
        if (current?.mode == RecordingMode.RECORDING) {
            runCatching {
                val paused = current.pause(SystemClock.elapsedRealtime())
                store.save(paused, null, paused.elapsedMillis)
                RecordingSession.publish(paused)
            }
        }
        scope.cancel()
        store.close()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPS 轨迹录制", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification {
        val current = recording
        val startTime = current?.startedAtMillis?.takeIf { it > 0L }?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "--"
        val distance = current?.let(::notificationDistance) ?: "0 m"
        val actionHint = if (RecordingSession.controlLockState.value.isLocked) {
            "点击通知返回应用，连点锁图标 3 次后操作"
        } else {
            "点击通知返回应用进行暂停或结束操作"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording_notification)
            .setContentTitle("GPS录制中")
            .setContentText("已录制距离：$distance · 开始时间：$startTime")
            .setSubText("点击通知返回应用操作")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "开始时间：$startTime\n已录制距离：$distance\n$actionHint",
            ))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return builder.build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification())
        lastNotificationDistance = recording?.let(::notificationDistance) ?: "0 m"
        lastNotificationUpdateMillis = SystemClock.elapsedRealtime()
        lastNotificationLocked = RecordingSession.controlLockState.value.isLocked
    }

    private fun notificationDistance(track: TrackRecording): String =
        if (track.distanceMeters < 1_000.0) {
            "${track.distanceMeters.toInt()} m"
        } else {
            String.format(Locale.getDefault(), "%.2f km", track.distanceMeters / 1_000.0)
        }

    private fun updateOverlay(track: TrackRecording) {
        runCatching { overlay.update(track, lastLocationSample) }
            .onFailure { overlay.remove() }
    }

    private fun TrackRecording.ageMillis(): Long =
        (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)

    companion object {
        const val ACTION_START = "org.opengpxtracker.android.action.START_RECORDING"
        const val ACTION_RESUME = "org.opengpxtracker.android.action.RESUME_RECORDING"
        const val ACTION_PAUSE = "org.opengpxtracker.android.action.PAUSE_RECORDING"
        const val ACTION_FINISH = "org.opengpxtracker.android.action.FINISH_RECORDING"
        const val ACTION_UNLOCK_TAP = "org.opengpxtracker.android.action.UNLOCK_TAP"
        const val ACTION_REFRESH_OVERLAY = "org.opengpxtracker.android.action.REFRESH_OVERLAY"
        private const val EXTRA_TRACK_ID = "track_id"
        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 101
        private const val LOCATION_RETRY_INTERVAL_MILLIS = 10_000L
        private const val ELAPSED_SAVE_INTERVAL_MILLIS = 5_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 5_000L

        fun command(context: Context, action: String, id: Long? = null) {
            val intent = commandIntent(context, action, id)
            if (action == ACTION_START || action == ACTION_RESUME) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        private fun commandIntent(context: Context, action: String, id: Long?): Intent =
            Intent(context, RecordingService::class.java).setAction(action).apply {
                if (id != null) putExtra(EXTRA_TRACK_ID, id)
            }
    }
}
