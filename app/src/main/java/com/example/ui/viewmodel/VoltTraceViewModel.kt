package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.hardware.EnergyMeterInventory
import com.example.data.hardware.ObservedAppUsage
import com.example.data.local.BatterySampleEntity
import com.example.data.local.EnergyMeterSnapshotEntity
import com.example.data.local.UserMonitorSettings
import com.example.data.repository.BatteryRepository
import com.example.domain.AnalysisWindow
import com.example.domain.BatteryAnalysisReport
import com.example.domain.ExportFormat
import com.example.domain.HardwareSubsystemStatus
import com.example.domain.OvernightReplayEngine
import com.example.domain.OvernightReplayReport
import com.example.domain.ProbeSessionResult
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SpikeForensicReport
import com.example.domain.SpikeInspector
import com.example.domain.WatchInvestigationConfig
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProbeUiState(
    val isRunning: Boolean = false,
    val elapsedSeconds: Int = 0,
    val totalSeconds: Int = 30,
    val result: ProbeSessionResult? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class VoltTraceViewModel(
    private val repository: BatteryRepository,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val _selectedWindow = MutableStateFlow(AnalysisWindow.TWENTY_FOUR_HOURS)
    val selectedWindow: StateFlow<AnalysisWindow> = _selectedWindow.asStateFlow()

    private val _windowClockTickMs = MutableStateFlow(clockMs())

    private val _liveSample = MutableStateFlow(repository.readLiveSample("INIT"))
    val liveSample: StateFlow<BatterySampleEntity> = _liveSample.asStateFlow()

    private val _liveSubsystems = MutableStateFlow(
        RootCauseAnalyzer.estimateLiveSubsystems(_liveSample.value)
    )
    val liveSubsystems: StateFlow<List<HardwareSubsystemStatus>> = _liveSubsystems.asStateFlow()

    private val _oscilloscopeMw = MutableStateFlow(
        listOf(effectiveOscilloscopeMw(_liveSample.value))
    )
    val oscilloscopeMw: StateFlow<List<Float>> = _oscilloscopeMw.asStateFlow()

    private val _hasUsageAccess = MutableStateFlow(repository.hasUsageStatsPermission())
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    private val _liveAppUsage = MutableStateFlow<List<ObservedAppUsage>>(emptyList())

    private val _probeState = MutableStateFlow(ProbeUiState())
    val probeState: StateFlow<ProbeUiState> = _probeState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _energyMeterInventory = MutableStateFlow<EnergyMeterInventory?>(null)
    val energyMeterInventory: StateFlow<EnergyMeterInventory?> = _energyMeterInventory.asStateFlow()

    private val _selectedSpikeReport = MutableStateFlow<SpikeForensicReport?>(null)
    val selectedSpikeReport: StateFlow<SpikeForensicReport?> = _selectedSpikeReport.asStateFlow()

    private val _isInvestigatingSpike = MutableStateFlow(false)
    val isInvestigatingSpike: StateFlow<Boolean> = _isInvestigatingSpike.asStateFlow()

    private val _showOvernightReplaySheet = MutableStateFlow(false)
    val showOvernightReplaySheet: StateFlow<Boolean> = _showOvernightReplaySheet.asStateFlow()

    private var livePollingJob: Job? = null
    private var probeJob: Job? = null
    private var spikeJob: Job? = null

    val isLivePollingActive: Boolean
        get() = livePollingJob?.isActive == true

    val settings: StateFlow<UserMonitorSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserMonitorSettings()
    )

    val totalSampleCount: StateFlow<Int> = repository.totalSampleCountFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    internal val windowSinceMsFlow = combine(
        _selectedWindow,
        _windowClockTickMs
    ) { window, nowMs ->
        val duration = window.durationMs
        if (duration == null) 0L else (nowMs - duration).coerceAtLeast(0L)
    }.distinctUntilChanged()

    val windowSamples: StateFlow<List<BatterySampleEntity>> = windowSinceMsFlow
        .flatMapLatest { sinceMs -> repository.observeSamplesSince(sinceMs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val windowAppEvents = windowSinceMsFlow
        .flatMapLatest { sinceMs -> repository.observeAppEventsSince(sinceMs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val windowEnergyMeters: StateFlow<List<EnergyMeterSnapshotEntity>> = windowSinceMsFlow
        .flatMapLatest { sinceMs -> repository.observeEnergyMetersSince(sinceMs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val analysisReport: StateFlow<BatteryAnalysisReport> = combine(
        combine(windowSamples, _liveSample, windowAppEvents) { samples, live, events ->
            Triple(samples, live, events)
        },
        combine(_liveAppUsage, _selectedWindow, settings) { liveApps, window, userSettings ->
            Triple(liveApps, window, userSettings)
        },
        windowEnergyMeters
    ) { (samples, live, events), (liveApps, window, userSettings), meters ->
        RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = live,
            appEvents = events,
            liveAppUsage = liveApps,
            window = window,
            ratedCapacityMah = userSettings.ratedCapacityMah,
            thermalAlertCelsius = userSettings.thermalAlertCelsius,
            energyMeterSnapshots = meters
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RootCauseAnalyzer.analyze(
            samples = emptyList(),
            latestSample = _liveSample.value,
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.TWENTY_FOUR_HOURS,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )
    )

    private val baselineSamples: StateFlow<List<BatterySampleEntity>> = _windowClockTickMs
        .map { nowMs -> (nowMs - 14L * 24L * 60L * 60L * 1000L).coerceAtLeast(0L) }
        .distinctUntilChanged()
        .flatMapLatest { sinceMs -> repository.observeSamplesSince(sinceMs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val recentOvernightAppEvents = _windowClockTickMs
        .map { nowMs -> (nowMs - 48L * 60L * 60L * 1000L).coerceAtLeast(0L) }
        .distinctUntilChanged()
        .flatMapLatest { sinceMs -> repository.observeAppEventsSince(sinceMs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val overnightReport: StateFlow<OvernightReplayReport> = combine(
        baselineSamples,
        recentOvernightAppEvents,
        settings
    ) { allSamples, appEvents, userSettings ->
        val report = OvernightReplayEngine.analyze(
            allSamples = allSamples,
            appEvents = appEvents,
            watchConfig = userSettings.watchConfig,
            ratedCapacityMah = userSettings.ratedCapacityMah
        )
        val matchEventId = report.watchMatchEventId
        val matchEventTs = report.watchMatchEventTimestampMs
        if (report.watchTriggeredThisWindow &&
            matchEventId != null &&
            matchEventTs != null &&
            matchEventId != userSettings.watchConfig.lastMatchedEventId &&
            matchEventTs > userSettings.watchConfig.armedAtMs &&
            !report.watchMatchReason.isNullOrBlank()
        ) {
            viewModelScope.launch {
                repository.recordWatchMatch(matchEventId, matchEventTs, report.watchMatchReason)
            }
        }
        report
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OvernightReplayEngine.analyze(emptyList())
    )

    init {
        viewModelScope.launch {
            repository.recordSampleIfSignificant(_liveSample.value, minIntervalMs = 0L)
            repository.ensureHighPrecisionServiceSynced()
            refreshUsageAccessAndApps()
            scanEnergyMeters(showToast = false)
        }
    }

    /**
     * Ties the 2-second live hardware polling loop strictly to the visible Activity lifecycle
     * (ON_START / ON_STOP) so VoltTrace performs zero background polling when left closed
     * unless the user explicitly enabled WorkManager or the 1-min Precision Monitor.
     */
    fun setForegroundActive(active: Boolean) {
        if (active) {
            advanceWindowClock()
            if (livePollingJob?.isActive == true) return
            livePollingJob = viewModelScope.launch {
                var ticks = 0
                while (isActive) {
                    delay(2_000L)
                    ticks++
                    val sample = repository.readLiveSample("LIVE")
                    _liveSample.value = sample
                    _liveSubsystems.value = RootCauseAnalyzer.estimateLiveSubsystems(sample)
                    val traceMw = effectiveOscilloscopeMw(sample)
                    _oscilloscopeMw.update { current ->
                        (current + traceMw).takeLast(48)
                    }
                    // Roll time-window queries forward every 30s while the app stays open
                    if (ticks % 15 == 0) {
                        advanceWindowClock()
                    }
                    // Persist meaningful longitudinal transitions or every 60 seconds while open
                    if (ticks % 30 == 0) {
                        repository.recordSampleIfSignificant(sample, minIntervalMs = 55_000L)
                    } else {
                        repository.recordSampleIfSignificant(sample, minIntervalMs = 180_000L)
                    }
                }
            }
        } else {
            livePollingJob?.cancel()
            livePollingJob = null
        }
    }

    fun advanceWindowClock(nowMs: Long = clockMs()) {
        _windowClockTickMs.value = nowMs
        if (_hasUsageAccess.value) {
            refreshLiveAppUsageForClock(nowMs)
        }
    }

    override fun onCleared() {
        livePollingJob?.cancel()
        livePollingJob = null
        super.onCleared()
    }

    private fun effectiveOscilloscopeMw(sample: BatterySampleEntity): Float {
        return if (sample.powerMw > 25f) {
            sample.powerMw
        } else {
            RootCauseAnalyzer.estimateInstantHardwareMw(sample).values.sum() + 110f
        }
    }

    fun selectWindow(window: AnalysisWindow) {
        _selectedWindow.value = window
        val now = clockMs()
        _windowClockTickMs.value = now
        refreshUsageAccessAndApps(now)
    }

    private fun refreshLiveAppUsageForClock(nowMs: Long = clockMs()) {
        viewModelScope.launch {
            val duration = _selectedWindow.value.durationMs ?: (7L * 24L * 60L * 60L * 1000L)
            val since = (nowMs - duration).coerceAtLeast(0L)
            _liveAppUsage.value = repository.queryAppUsageRange(since, nowMs)
        }
    }

    fun refreshUsageAccessAndApps(nowMs: Long = clockMs()) {
        val granted = repository.hasUsageStatsPermission()
        _hasUsageAccess.value = granted
        if (granted) {
            refreshLiveAppUsageForClock(nowMs)
        } else {
            _liveAppUsage.value = emptyList()
        }
    }

    fun syncPrecisionService() {
        viewModelScope.launch {
            repository.ensureHighPrecisionServiceSynced()
        }
    }

    fun inspectTimelineSample(sampleIndex: Int) {
        val samples = windowSamples.value
        if (samples.isEmpty() || sampleIndex !in samples.indices) return
        val (windowStartMs, windowEndMs) = SpikeInspector.computeSpikeWindowBounds(samples, sampleIndex)

        spikeJob?.cancel()
        spikeJob = viewModelScope.launch {
            _isInvestigatingSpike.value = true
            val usageGranted = repository.hasUsageStatsPermission()
            _hasUsageAccess.value = usageGranted

            val events = if (usageGranted) {
                runCatching { repository.queryDetailedEventsAround(windowStartMs, windowEndMs) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val apps = if (usageGranted) {
                runCatching { repository.queryAppUsageRange(windowStartMs, windowEndMs) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val spikeMeters = runCatching {
                repository.getEnergyMetersInRange(
                    windowStartMs - 20L * 60_000L,
                    windowEndMs + 20L * 60_000L
                )
            }.getOrDefault(emptyList()).ifEmpty { windowEnergyMeters.value }
            val spikeAppEvents = runCatching {
                repository.getAppEventsInRange(
                    windowStartMs - 4L * 60L * 60_000L,
                    windowEndMs + 60_000L
                )
            }.getOrDefault(emptyList()).ifEmpty { windowAppEvents.value }

            _selectedSpikeReport.value = SpikeInspector.analyzeSpike(
                samples = samples,
                targetIndex = sampleIndex,
                usageEvents = events,
                appUsage = apps,
                appDrainEvents = spikeAppEvents,
                energyMeters = spikeMeters,
                hasUsageAccess = usageGranted,
                ratedCapacityMah = settings.value.ratedCapacityMah
            )
            _isInvestigatingSpike.value = false
        }
    }

    fun dismissSpikeInspection() {
        spikeJob?.cancel()
        _isInvestigatingSpike.value = false
        _selectedSpikeReport.value = null
    }

    fun openOvernightReplay() {
        if (overnightReport.value.samples.isNotEmpty()) {
            _showOvernightReplaySheet.value = true
        }
    }

    fun dismissOvernightReplay() {
        _showOvernightReplaySheet.value = false
    }

    fun toggleWatchForThisAgain() {
        val currentWatch = settings.value.watchConfig
        val report = overnightReport.value
        viewModelScope.launch {
            if (currentWatch.active) {
                repository.clearWatchInvestigation()
                _snackbarMessage.value = "Watch investigation cleared"
            } else {
                val config = WatchInvestigationConfig(
                    active = true,
                    title = report.suggestedWatchTitle,
                    suspectLabel = report.suggestedWatchSuspect,
                    suspectPackage = report.suggestedWatchPackage,
                    triggerDrainPctPerHour = report.suggestedTriggerRatePctHr,
                    triggerPeakMw = report.suggestedTriggerPeakMw,
                    armedAtMs = clockMs(),
                    matchCount = 0,
                    lastMatchedAtMs = 0L,
                    lastMatchSummary = ""
                )
                repository.armWatchInvestigation(config)
                _snackbarMessage.value = "Armed: ${config.title}"
            }
        }
    }

    fun inspectOvernightSample(sample: BatterySampleEntity) {
        val samples = overnightReport.value.samples
        val idx = samples.indexOfFirst { it.id == sample.id && it.timestampMs == sample.timestampMs }
            .takeIf { it >= 0 } ?: return
        _showOvernightReplaySheet.value = false
        val (windowStartMs, windowEndMs) = SpikeInspector.computeSpikeWindowBounds(samples, idx)

        spikeJob?.cancel()
        spikeJob = viewModelScope.launch {
            _isInvestigatingSpike.value = true
            val usageGranted = repository.hasUsageStatsPermission()
            _hasUsageAccess.value = usageGranted
            val events = if (usageGranted) {
                runCatching { repository.queryDetailedEventsAround(windowStartMs, windowEndMs) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val apps = if (usageGranted) {
                runCatching { repository.queryAppUsageRange(windowStartMs, windowEndMs) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val spikeMeters = runCatching {
                repository.getEnergyMetersInRange(
                    windowStartMs - 20L * 60_000L,
                    windowEndMs + 20L * 60_000L
                )
            }.getOrDefault(emptyList()).ifEmpty { windowEnergyMeters.value }
            val spikeAppEvents = runCatching {
                repository.getAppEventsInRange(
                    windowStartMs - 4L * 60L * 60_000L,
                    windowEndMs + 60_000L
                )
            }.getOrDefault(emptyList()).ifEmpty { recentOvernightAppEvents.value }

            _selectedSpikeReport.value = SpikeInspector.analyzeSpike(
                samples = samples,
                targetIndex = idx,
                usageEvents = events,
                appUsage = apps,
                appDrainEvents = spikeAppEvents,
                energyMeters = spikeMeters,
                hasUsageAccess = usageGranted,
                ratedCapacityMah = settings.value.ratedCapacityMah
            )
            _isInvestigatingSpike.value = false
        }
    }

    fun scanEnergyMeters(showToast: Boolean = true) {
        viewModelScope.launch {
            val inventory = repository.discoverEnergyMeters()
            _energyMeterInventory.value = inventory
            if (showToast) {
                _snackbarMessage.value = if (inventory.meters.isNotEmpty()) {
                    "Discovered ${inventory.meters.size} hardware energy meters"
                } else {
                    inventory.statusMessage
                }
            }
        }
    }

    fun captureManualSnapshot() {
        viewModelScope.launch {
            val sample = repository.readLiveSample("MANUAL")
            repository.recordSample(sample)
            _liveSample.value = sample
            _liveSubsystems.value = RootCauseAnalyzer.estimateLiveSubsystems(sample)
            _snackbarMessage.value = "Telemetry snapshot logged to timeline"
        }
    }

    fun toggleDrainProbe() {
        if (_probeState.value.isRunning) {
            probeJob?.cancel()
            _probeState.update { it.copy(isRunning = false) }
            return
        }
        probeJob = viewModelScope.launch {
            val totalSec = 30
            val captured = mutableListOf<BatterySampleEntity>()
            _probeState.value = ProbeUiState(
                isRunning = true,
                elapsedSeconds = 0,
                totalSeconds = totalSec,
                result = null
            )
            for (sec in 0..totalSec) {
                val sample = repository.readLiveSample("PROBE")
                captured.add(sample)
                _liveSample.value = sample
                _liveSubsystems.value = RootCauseAnalyzer.estimateLiveSubsystems(sample)
                if (sec == 0 || sec == totalSec || sec % 10 == 0) {
                    repository.recordSample(sample)
                }
                _probeState.update { it.copy(elapsedSeconds = sec) }
                if (sec < totalSec) delay(1_000L)
            }
            val result = RootCauseAnalyzer.analyzeProbeSession(
                probeSamples = captured,
                ratedCapacityMah = settings.value.ratedCapacityMah
            )
            _probeState.value = ProbeUiState(
                isRunning = false,
                elapsedSeconds = totalSec,
                totalSeconds = totalSec,
                result = result
            )
            _snackbarMessage.value = "30s drain probe complete"
        }
    }

    fun setBackgroundMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            repository.setBackgroundMonitoring(enabled)
        }
    }

    fun setHighPrecisionMonitor(enabled: Boolean) {
        viewModelScope.launch {
            repository.setHighPrecisionMonitor(enabled)
            _snackbarMessage.value = if (enabled) {
                "1-Minute High-Precision Monitor active"
            } else {
                "1-Minute Precision Monitor stopped"
            }
        }
    }

    fun setCriticalAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setCriticalAlertsEnabled(enabled)
            _snackbarMessage.value = if (enabled) {
                "Critical thermal & standby drain alerts enabled"
            } else {
                "Critical alerts muted"
            }
        }
    }

    fun exportDiagnosticReport(
        format: ExportFormat,
        onUriReady: (Uri, ExportFormat) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                repository.exportDiagnosticReport(
                    format = format,
                    report = analysisReport.value,
                    samples = windowSamples.value.ifEmpty { listOf(_liveSample.value) },
                    latestSample = _liveSample.value
                )
            }.onSuccess { uri ->
                onUriReady(uri, format)
            }.onFailure {
                _snackbarMessage.value = "Could not export report"
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repository.setThemeMode(mode)
        }
    }

    fun setThermalAlertCelsius(tempC: Float) {
        viewModelScope.launch {
            repository.setThermalAlertCelsius(tempC)
        }
    }

    fun setRatedCapacityMah(capacityMah: Int) {
        viewModelScope.launch {
            repository.setRatedCapacityMah(capacityMah)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            val fresh = repository.readLiveSample("RESET")
            repository.recordSample(fresh)
            _liveSample.value = fresh
            _snackbarMessage.value = "Telemetry history cleared"
        }
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    class Factory(private val repository: BatteryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VoltTraceViewModel(repository) as T
        }
    }
}
