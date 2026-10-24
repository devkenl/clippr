package app.clippr.core.model

/**
 * Checks the invariants every edit command is expected to preserve. Used by tests and when a
 * project is loaded, so a damaged document is reported instead of producing a broken render.
 */
object ProjectIntegrity {

    fun problems(project: Project): List<String> {
        val issues = ArrayList<String>()
        val assetIds = project.assets.map { it.id }.toSet()
        val clipIds = HashSet<String>()
        val trackIds = HashSet<String>()
        if (project.sequence.tracks.none { it.kind == TrackKind.VIDEO }) issues += "No video track"
        for (track in project.sequence.tracks) {
            if (!trackIds.add(track.id)) issues += "Duplicate track id ${track.id}"
            var prevEnd = 0L
            var prevStart = -1L
            for (clip in track.clips) {
                if (!clipIds.add(clip.id)) issues += "Duplicate clip id ${clip.id}"
                if (clip.timelineStartUs < prevStart) issues += "Clips out of order on ${track.id}"
                if (clip.timelineStartUs < prevEnd) issues += "Clip ${clip.id} overlaps previous clip on ${track.id}"
                prevEnd = clip.timelineEndUs
                prevStart = clip.timelineStartUs
                when (track.kind) {
                    TrackKind.TEXT -> if (clip.text == null) issues += "Clip ${clip.id} on text track has no text"
                    else -> {
                        val asset = project.asset(clip.assetId)
                        if (clip.assetId == null || clip.assetId !in assetIds || asset == null) {
                            issues += "Clip ${clip.id} references unknown asset ${clip.assetId}"
                        } else {
                            if (!track.accepts(asset.type)) issues += "Clip ${clip.id} (${asset.type}) on ${track.kind} track"
                            if (!asset.isUnbounded && asset.durationUs > 0 && clip.sourceEndUs > asset.durationUs) {
                                issues += "Clip ${clip.id} reads past end of ${asset.id}"
                            }
                        }
                    }
                }
                if (clip.sourceStartUs < 0) issues += "Clip ${clip.id} has negative source start"
            }
        }
        return issues
    }

    fun isValid(project: Project): Boolean = problems(project).isEmpty()
}
