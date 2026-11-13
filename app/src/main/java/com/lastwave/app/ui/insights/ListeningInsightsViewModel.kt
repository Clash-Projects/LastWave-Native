package com.lastwave.app.ui.insights

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.repository.ListeningInsights
import com.lastwave.app.data.repository.ListeningInsightsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
sealed interface ListeningInsightsUiState {
    data object Loading : ListeningInsightsUiState
    data object Empty : ListeningInsightsUiState
    @Immutable
    data class Content(val insights: ListeningInsights) : ListeningInsightsUiState
    @Immutable
    data class Error(val message: String) : ListeningInsightsUiState
}

@HiltViewModel
class ListeningInsightsViewModel @Inject constructor(
    repository: ListeningInsightsRepository,
) : ViewModel() {

    val uiState: StateFlow<ListeningInsightsUiState> =
        repository.observeInsights()
            .map<ListeningInsights, ListeningInsightsUiState> { insights ->
                if (insights.isEmpty) {
                    ListeningInsightsUiState.Empty
                } else {
                    ListeningInsightsUiState.Content(insights)
                }
            }
            .catch { throwable ->
                emit(ListeningInsightsUiState.Error(throwable.message ?: "Unable to load listening insights"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ListeningInsightsUiState.Loading,
            )
}

