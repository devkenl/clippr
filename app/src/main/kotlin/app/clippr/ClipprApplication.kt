package app.clippr

import android.app.Application
import android.content.ComponentCallbacks2
import app.clippr.core.database.ClipprDatabase
import app.clippr.core.database.ProjectRepository
import app.clippr.core.media.FrameGrabber
import app.clippr.core.media.MediaAccess
import app.clippr.core.media.MediaProbe
import app.clippr.core.media.ThumbnailRepository
import app.clippr.core.media.VideoLibrary
import app.clippr.core.media.WaveformRepository
import app.clippr.core.render.ExportEngine
import app.clippr.core.render.RenderResources
import app.clippr.feature.editor.EditorDependencies
import app.clippr.feature.export.ExportCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. The graph is small and explicit, so a DI framework would add
 * build complexity (annotation processing, generated code) without simplifying anything yet.
 */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database: ClipprDatabase by lazy { ClipprDatabase.create(app) }
    val repository: ProjectRepository by lazy { ProjectRepository(database.projectDao()) }
    val thumbnails = ThumbnailRepository(app)
    val waveforms = WaveformRepository(app)
    val videoLibrary = VideoLibrary(app)
    val editorDependencies: EditorDependencies by lazy {
        EditorDependencies(
            context = app,
            repository = repository,
            probe = MediaProbe(app),
            access = MediaAccess(app),
            thumbnails = thumbnails,
            waveforms = waveforms,
            appScope = appScope,
            resources = RenderResources.get(app),
            frameGrabber = FrameGrabber(app),
        )
    }
    val exportCoordinator: ExportCoordinator by lazy {
        ExportCoordinator(app, ExportEngine(app), videoLibrary, appScope)
    }
}

class ClipprApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) container.thumbnails.trimMemory()
    }
}
