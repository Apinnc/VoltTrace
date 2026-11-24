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
import com.example.domain.ProbeSessionResult
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SpikeForensicReport
import com.example.domain.SpikeInspector
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
    private val repository: BatteryRepository
) : ViewModel() {

    private val _selectedWindow = MutableStateFlow(AnalysisWindow.TWENTY_FOUR_HOURS)
    val selectedWindow: StateFlow<AnalysisWindow> = _selectedWindow.asStateFlow()

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

    private var probeJob: Job? = null
    private var spikeJob: Job? = null

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

    private val windowSinceMsFlow = _selectedWindow.combine(
        MutableStateFlow(Unit)
    ) { window, _ ->
        val duration = window.durationMs
        if (duration == null) 0L else (System.currentTimeMillis() - duration)
    }

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

    init {
        viewModelScope.launch {
            repository.recordSampleIfSignificant(_liveSample.value, minIntervalMs = 0L)
            repository.ensureHighPrecisionServiceSynced()
            refreshUsageAccessAndApps()
            scanEnergyMeters(showToast = false)
        }
        startLiveTelemetryLoop()
    }

    private fun startLiveTelemetryLoop() {
        viewModelScope.launch {
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
                // Persist meaningful longitudinal transitions or every 60 seconds while open
                if (ticks % 30 == 0) {
                    repository.recordSampleIfSignificant(sample, minIntervalMs = 55_000L)
                } else {
                    repository.recordSampleIfSignificant(sample, minIntervalMs = 180_000L)
                }
            }
        }
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
        refreshUsageAccessAndApps()
    }

    fun refreshUsageAccessAndApps() {
        val granted = repository.hasUsageStatsPermission()
        _hasUsageAccess.value = granted
        if (granted) {
            viewModelScope.launch {
                val duration = _selectedWindow.value.durationMs ?: (7L * 24L * 60L * 60L * 1000L)
                val since = System.currentTimeMillis() - duration
                _liveAppUsage.value = repository.queryRecentAppUsage(since)
            }
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
        val target = samples[sampleIndex]
        val prev = samples.getOrNull(sampleIndex - 1)
        val next = samples.getOrNull(sampleIndex + 1)

        val queryStartMs = (prev?.timestampMs ?: (target.timestampMs - 10L * 60_000L))
            .coerceAtLeast(target.timestampMs - 20L * 60_000L)
            .coerceAtMost(target.timestampMs - 5L * 60_000L)
        val queryEndMs = (next?.timestampMs ?: (target.timestampMs + 5L * 60_000L))
            .coerceAtMost(target.timestampMs + 10L * 60_000L)
            .coerceAtLeast(target.timestampMs + 3L * 60_000L)

        spikeJob?.cancel()
        spikeJob = viewModelScope.launch {
            _isInvestigatingSpike.value = true
            val usageGranted = repository.hasUsageStatsPermission()
            _hasUsageAccess.value = usageGranted

            val events = if (usageGranted) {
                runCatching { repository.queryDetailedEventsAround(queryStartMs, queryEndMs) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val apps = if (usageGranted) {
                runCatching { repository.queryAppUsageRange(queryStartMs, queryEndMs) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            _selectedSpikeReport.value = SpikeInspector.analyzeSpike(
                samples = samples,
                targetIndex = sampleIndex,
                usageEvents = events,
                appUsage = apps,
                appDrainEvents = windowAppEvents.value,
                energyMeters = windowEnergyMeters.value,
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
