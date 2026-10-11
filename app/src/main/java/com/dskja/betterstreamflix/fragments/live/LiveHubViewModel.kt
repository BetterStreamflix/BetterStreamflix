package com.dskja.betterstreamflix.fragments.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class LiveHubViewModel : ViewModel() {

    private val _state = MutableSharedFlow<State>(replay = 1)
    val state = _state.asSharedFlow()

    private var loadJob: Job? = null

    sealed class State {
        data object Loading : State()
        /** Kids / parental controls — no channels, no playback. */
        data object Restricted : State()
        data class Success(
            val categories: List<Category>,
            val warning: String? = null,
        ) : State()
        data class Failed(val error: Exception) : State()
    }

    fun load(
        sportLabel: String,
        newsLabel: String,
        channelsLabel: String,
    ) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (LiveHubCatalog.isAgeGated()) {
                _state.emit(State.Restricted)
                return@launch
            }
            _state.emit(State.Loading)
            try {
                val includeQuarantined = UserPreferences.showQuarantinedProviders
                val result = LiveHubCatalog.load(
                    sportLabel = sportLabel,
                    newsLabel = newsLabel,
                    channelsLabel = channelsLabel,
                    includeQuarantined = includeQuarantined,
                )
                if (result.categories.isEmpty()) {
                    _state.emit(
                        State.Failed(
                            Exception("No live channels available right now. Try again later."),
                        ),
                    )
                    return@launch
                }
                val warning = result.warnings.takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = " · ")
                _state.emit(State.Success(result.categories, warning))
            } catch (e: Exception) {
                _state.emit(State.Failed(e))
            }
        }
    }
}
