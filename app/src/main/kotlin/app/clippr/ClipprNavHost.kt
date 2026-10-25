package app.clippr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.clippr.feature.editor.EditorScreen
import app.clippr.feature.editor.EditorViewModel
import app.clippr.feature.export.ExportScreen
import app.clippr.feature.export.ExportViewModel
import app.clippr.feature.home.HomeScreen
import app.clippr.feature.home.HomeViewModel

/** Screens are identified by string keys: "home", "editor/<id>", "export/<id>". */
private object Routes {
    const val HOME = "home"
    fun editor(id: String) = "editor/$id"
    fun export(id: String) = "export/$id"
}

/**
 * Keeps one [ViewModelStore] per back-stack entry, so a screen's ViewModels (and the editor's
 * preview player) are released as soon as the screen is popped, not when the activity ends.
 */
class ScreenStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    fun retainOnly(keys: Collection<String>) {
        (stores.keys - keys.toSet()).forEach { clear(it) }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Minimal navigation for three destinations. Avoids a navigation library while giving each
 * entry its own ViewModel scope and saved UI state.
 */
@Composable
fun ClipprNavHost(container: AppContainer, openProjectId: String? = null) {
    val backStack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) {
        if (openProjectId != null) mutableStateListOf(Routes.HOME, Routes.editor(openProjectId)) else mutableStateListOf(Routes.HOME)
    }
    val screenStores: ScreenStores = viewModel()
    val saveableState = rememberSaveableStateHolder()

    fun push(route: String) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun pop() {
        if (backStack.size <= 1) return
        val removed = backStack.removeAt(backStack.lastIndex)
        if (removed !in backStack) {
            screenStores.clear(removed)
            saveableState.removeState(removed)
        }
    }

    val current = backStack.last()
    saveableState.SaveableStateProvider(current) {
        val owner = remember(current) {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = screenStores.storeFor(current)
            }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            when {
                current == Routes.HOME -> {
                    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container.homeDependencies) } })
                    HomeScreen(vm, versionName = BuildConfig.VERSION_NAME, onOpenProject = { push(Routes.editor(it)) })
                }
                current.startsWith("editor/") -> {
                    val id = current.removePrefix("editor/")
                    val vm: EditorViewModel = viewModel(
                        factory = viewModelFactory { initializer { EditorViewModel(id, container.editorDependencies) } },
                    )
                    EditorScreen(vm, onBack = ::pop, onExport = { push(Routes.export(it)) })
                }
                current.startsWith("export/") -> {
                    val id = current.removePrefix("export/")
                    val vm: ExportViewModel = viewModel(
                        factory = viewModelFactory { initializer { ExportViewModel(id, container.repository, container.exportCoordinator, container.settings.current.exportQuality) } },
                    )
                    ExportScreen(vm, container.videoLibrary, onBack = ::pop)
                }
                else -> {
                    // Unknown route (e.g. from an older saved state): reset to Home.
                    backStack.clear()
                    backStack.add(Routes.HOME)
                }
            }
        }
    }
}
