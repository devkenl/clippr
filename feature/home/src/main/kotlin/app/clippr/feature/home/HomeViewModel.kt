package app.clippr.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clippr.core.database.ProjectRepository
import app.clippr.core.database.ProjectSummary
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.time.FrameRate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeState {
    data object Loading : HomeState
    data class Ready(val projects: List<ProjectSummary>) : HomeState
}

class HomeViewModel(private val repository: ProjectRepository) : ViewModel() {

    val state: StateFlow<HomeState> = repository.observeProjects()
        .map<List<ProjectSummary>, HomeState> { HomeState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState.Loading)

    /** Creates a project and reports its id so the caller can open the editor. */
    fun create(name: String, preset: AspectRatioPreset, shortSide: Int, frameRate: FrameRate, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val project = repository.create(name, ProjectSettings.create(preset, shortSide, frameRate))
            onCreated(project.id)
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { repository.duplicate(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
