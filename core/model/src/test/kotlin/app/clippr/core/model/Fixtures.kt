package app.clippr.core.model

import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.US_PER_SECOND

const val SEC = US_PER_SECOND

object Fixtures {
    val video10s = MediaAsset(
        id = "a-video-10",
        uri = "content://media/video/10",
        displayName = "beach.mp4",
        type = MediaType.VIDEO,
        durationUs = 10 * SEC,
        width = 1920,
        height = 1080,
        frameRate = 30f,
        mimeType = "video/mp4",
    )
    val video6s = video10s.copy(id = "a-video-6", uri = "content://media/video/6", displayName = "city.mp4", durationUs = 6 * SEC)
    val silentVideo = video10s.copy(id = "a-silent", uri = "content://media/video/silent", hasAudio = false)
    val music = MediaAsset(
        id = "a-music",
        uri = "content://media/audio/1",
        displayName = "song.m4a",
        type = MediaType.AUDIO,
        durationUs = 30 * SEC,
        mimeType = "audio/mp4",
    )
    val photo = MediaAsset(
        id = "a-photo",
        uri = "content://media/image/1",
        displayName = "photo.jpg",
        type = MediaType.IMAGE,
        durationUs = 0,
        width = 4000,
        height = 3000,
    )

    fun emptyProject(): Project = Project(
        id = "p1",
        name = "Test",
        settings = ProjectSettings(1920, 1080, FrameRate.FPS_30),
        assets = listOf(video10s, video6s, silentVideo, music, photo),
    )

    const val V1 = TimelineSequence.MAIN_VIDEO_TRACK_ID
    const val A1 = TimelineSequence.MAIN_AUDIO_TRACK_ID
    const val T1 = TimelineSequence.MAIN_TEXT_TRACK_ID
}

fun Project.track(id: String): Track = sequence.track(id)!!
fun Project.clip(id: String): Clip = sequence.clip(id)!!
