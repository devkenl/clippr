package app.clippr.feature.editor

import android.content.Context
import android.content.Intent

/** Relaunches Clippr in a fresh process. */
object AppRestart {
    /** Intent extra: the project to reopen in the editor after the restart. */
    const val EXTRA_PROJECT_ID = "app.clippr.extra.PROJECT_ID"

    fun restart(context: Context, projectId: String?) {
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        projectId?.let { launch.putExtra(EXTRA_PROJECT_ID, it) }
        app.startActivity(launch)
        // Ending the process is what releases decoders held by native code.
        Runtime.getRuntime().exit(0)
    }
}
