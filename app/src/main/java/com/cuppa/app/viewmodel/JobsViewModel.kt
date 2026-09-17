package com.cuppa.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuppa.app.data.CupsRepository
import com.cuppa.app.data.JobHistoryEntry
import com.cuppa.cups.PrintJob
import com.cuppa.cups.PrintJobStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class JobsUiState(
    val activeJobs: List<PrintJob> = emptyList(),
    val history: List<JobHistoryEntry> = emptyList(),
)

/**
 * ViewModel for the Jobs screen — combines the live/active job queue with persisted job
 * history (see CupsRepository.jobHistory), and keeps the active queue fresh with a light
 * poll while the screen is visible, independent of PrintJobDispatcher's own background poll
 * (which only runs — and thus only refreshes CupsRepository.jobs — while the foreground
 * service is actively processing something).
 */
class JobsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CupsRepository.getInstance(application)

    val uiState: StateFlow<JobsUiState> = combine(
        repository.jobs, repository.jobHistory
    ) { active, history ->
        JobsUiState(
            activeJobs = active.filter { it.status == PrintJobStatus.PENDING || it.status == PrintJobStatus.PROCESSING || it.status == PrintJobStatus.HELD },
            history = history
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = JobsUiState()
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                repository.refreshJobs()
                delay(3000L)
            }
        }
    }

    fun clearHistory() {
        repository.clearJobHistory()
    }
}
