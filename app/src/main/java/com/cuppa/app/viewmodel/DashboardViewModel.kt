package com.cuppa.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuppa.app.data.CupsRepository
import com.cuppa.app.data.ServerState
import com.cuppa.app.service.CupsPrintService
import com.cuppa.cups.PrinterInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val serverState: ServerState = ServerState.Stopped,
    val nativeVersion: String = "",
    val printerCount: Int = 0,
    val activeJobCount: Int = 0,
    val printers: List<PrinterInfo> = emptyList(),
    val isTestingPrinter: Boolean = false,
    val printerTestResult: String? = null,
    val lastTestedPrinter: PrinterInfo? = null
)

class DashboardViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = CupsRepository.getInstance(application)

    private val _isTestingPrinter = MutableStateFlow(false)
    private val _printerTestResult = MutableStateFlow<String?>(null)
    private val _lastTestedPrinter = MutableStateFlow<PrinterInfo?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(repository.serverState, repository.printers, repository.jobs) { serverState, printers, jobs ->
            Triple(serverState, printers, jobs)
        },
        _isTestingPrinter,
        _printerTestResult,
        _lastTestedPrinter
    ) { (serverState, printers, jobs), isTesting, testResult, testedPrinter ->
        DashboardUiState(
            serverState = serverState,
            nativeVersion = repository.getNativeVersion(),
            printerCount = printers.size,
            activeJobCount = jobs.count { it.state == 3 || it.state == 5 },
            printers = printers,
            isTestingPrinter = isTesting,
            printerTestResult = testResult,
            lastTestedPrinter = testedPrinter
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(
            serverState = repository.serverState.value,
            nativeVersion = repository.getNativeVersion()
        )
    )

    init {
        repository.refreshServerState()
    }

    fun toggleServer() {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("cuppa_settings", Application.MODE_PRIVATE)
        if (repository.serverState.value is ServerState.Running) {
            prefs.edit().putBoolean("server_should_be_running", false).apply()
            CupsPrintService.stop(app)
        } else {
            prefs.edit().putBoolean("server_should_be_running", true).apply()
            CupsPrintService.start(app)
        }
    }

    fun testPrinterConnection(uri: String) {
        if (uri.isBlank()) return
        viewModelScope.launch {
            _isTestingPrinter.value = true
            _printerTestResult.value = null
            val result = repository.testPrinterConnection(uri.trim())
            result.onSuccess { info ->
                _lastTestedPrinter.value = info
                _printerTestResult.value = "Connected: ${info.name} (${info.makeAndModel}) - ${info.stateName}"
            }.onFailure { e ->
                _printerTestResult.value = "Connection failed: ${e.message}"
            }
            _isTestingPrinter.value = false
        }
    }

    fun clearTestResult() {
        _printerTestResult.value = null
    }
}
