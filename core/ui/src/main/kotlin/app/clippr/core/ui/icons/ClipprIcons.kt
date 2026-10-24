package app.clippr.core.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Clippr's original icon set: 24-unit grid, 1.75 stroke, round caps and joins. Icons are drawn
 * in black and tinted by `Icon`.
 */
object ClipprIcons {

    val Play by lazy {
        icon("Play") {
            filled {
                moveTo(8f, 5.2f)
                curveTo(8f, 4.4f, 8.9f, 3.9f, 9.6f, 4.4f)
                lineTo(19.2f, 11.1f)
                curveTo(19.8f, 11.5f, 19.8f, 12.5f, 19.2f, 12.9f)
                lineTo(9.6f, 19.6f)
                curveTo(8.9f, 20.1f, 8f, 19.6f, 8f, 18.8f)
                close()
            }
        }
    }

    val Pause by lazy {
        icon("Pause") {
            filled { roundRect(6.5f, 5f, 4f, 14f, 1.2f) }
            filled { roundRect(13.5f, 5f, 4f, 14f, 1.2f) }
        }
    }

    val SkipBack by lazy {
        icon("SkipBack") {
            stroke { moveTo(6f, 5f); verticalLineTo(19f) }
            filled {
                moveTo(18f, 6.2f); verticalLineTo(17.8f)
                curveTo(18f, 18.6f, 17.1f, 19f, 16.5f, 18.5f)
                lineTo(9.3f, 12.8f)
                curveTo(8.8f, 12.4f, 8.8f, 11.6f, 9.3f, 11.2f)
                lineTo(16.5f, 5.5f)
                curveTo(17.1f, 5f, 18f, 5.4f, 18f, 6.2f)
                close()
            }
        }
    }

    val SkipForward by lazy {
        icon("SkipForward") {
            stroke { moveTo(18f, 5f); verticalLineTo(19f) }
            filled {
                moveTo(6f, 6.2f); verticalLineTo(17.8f)
                curveTo(6f, 18.6f, 6.9f, 19f, 7.5f, 18.5f)
                lineTo(14.7f, 12.8f)
                curveTo(15.2f, 12.4f, 15.2f, 11.6f, 14.7f, 11.2f)
                lineTo(7.5f, 5.5f)
                curveTo(6.9f, 5f, 6f, 5.4f, 6f, 6.2f)
                close()
            }
        }
    }

    val Undo by lazy {
        icon("Undo") {
            stroke {
                moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
                moveTo(4f, 9f); horizontalLineTo(14.5f)
                curveTo(17.5f, 9f, 20f, 11.5f, 20f, 14.5f)
                curveTo(20f, 17.5f, 17.5f, 20f, 14.5f, 20f)
                horizontalLineTo(11f)
            }
        }
    }

    val Redo by lazy {
        icon("Redo") {
            stroke {
                moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f)
                moveTo(20f, 9f); horizontalLineTo(9.5f)
                curveTo(6.5f, 9f, 4f, 11.5f, 4f, 14.5f)
                curveTo(4f, 17.5f, 6.5f, 20f, 9.5f, 20f)
                horizontalLineTo(13f)
            }
        }
    }

    val Split by lazy {
        icon("Split") {
            stroke {
                roundRect(3f, 7f, 6.5f, 10f, 1.5f)
                roundRect(14.5f, 7f, 6.5f, 10f, 1.5f)
                moveTo(12f, 3.5f); verticalLineTo(20.5f)
            }
        }
    }

    val Trash by lazy {
        icon("Trash") {
            stroke {
                moveTo(4f, 7f); horizontalLineTo(20f)
                moveTo(9.5f, 7f); verticalLineTo(4.8f)
                curveTo(9.5f, 4.4f, 9.9f, 4f, 10.3f, 4f)
                horizontalLineTo(13.7f)
                curveTo(14.1f, 4f, 14.5f, 4.4f, 14.5f, 4.8f)
                verticalLineTo(7f)
                moveTo(6.2f, 7f); lineTo(7f, 18.6f)
                curveTo(7.1f, 19.4f, 7.7f, 20f, 8.5f, 20f)
                horizontalLineTo(15.5f)
                curveTo(16.3f, 20f, 16.9f, 19.4f, 17f, 18.6f)
                lineTo(17.8f, 7f)
                moveTo(10f, 11f); verticalLineTo(16f)
                moveTo(14f, 11f); verticalLineTo(16f)
            }
        }
    }

    val Plus by lazy {
        icon("Plus") { stroke { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) } }
    }

    val Minus by lazy {
        icon("Minus") { stroke { moveTo(5f, 12f); horizontalLineTo(19f) } }
    }

    val Close by lazy {
        icon("Close") { stroke { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    }

    val Check by lazy {
        icon("Check") { stroke { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    }

    val Back by lazy {
        icon("Back") { stroke { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    }

    val ChevronDown by lazy {
        icon("ChevronDown") { stroke { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }
    }

    val ChevronRight by lazy {
        icon("ChevronRight") { stroke { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) } }
    }

    val Film by lazy {
        icon("Film") {
            stroke {
                roundRect(3f, 4.5f, 18f, 15f, 2.5f)
                moveTo(7.5f, 4.5f); verticalLineTo(19.5f)
                moveTo(16.5f, 4.5f); verticalLineTo(19.5f)
                moveTo(3f, 9.5f); horizontalLineTo(7.5f)
                moveTo(3f, 14.5f); horizontalLineTo(7.5f)
                moveTo(16.5f, 9.5f); horizontalLineTo(21f)
                moveTo(16.5f, 14.5f); horizontalLineTo(21f)
            }
        }
    }

    val Photo by lazy {
        icon("Photo") {
            stroke {
                roundRect(3f, 4.5f, 18f, 15f, 2.5f)
                circle(9f, 9.5f, 1.6f)
                moveTo(21f, 15.5f); lineTo(16f, 10.5f); lineTo(6f, 19.5f)
            }
        }
    }

    val Music by lazy {
        icon("Music") {
            stroke {
                moveTo(9f, 17.5f); verticalLineTo(6f); lineTo(20f, 4f); verticalLineTo(15.5f)
                circle(6.5f, 17.5f, 2.5f)
                circle(17.5f, 15.5f, 2.5f)
                moveTo(9f, 9.5f); lineTo(20f, 7.5f)
            }
        }
    }

    val Text by lazy {
        icon("Text") {
            stroke {
                moveTo(5f, 7f); verticalLineTo(5f); horizontalLineTo(19f); verticalLineTo(7f)
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(9f, 19f); horizontalLineTo(15f)
            }
        }
    }

    val Import by lazy {
        icon("Import") {
            stroke {
                moveTo(12f, 4f); verticalLineTo(15f)
                moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
                moveTo(4.5f, 14.5f); verticalLineTo(17.5f)
                curveTo(4.5f, 18.9f, 5.6f, 20f, 7f, 20f)
                horizontalLineTo(17f)
                curveTo(18.4f, 20f, 19.5f, 18.9f, 19.5f, 17.5f)
                verticalLineTo(14.5f)
            }
        }
    }

    val Export by lazy {
        icon("Export") {
            stroke {
                moveTo(12f, 15f); verticalLineTo(4f)
                moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
                moveTo(4.5f, 14.5f); verticalLineTo(17.5f)
                curveTo(4.5f, 18.9f, 5.6f, 20f, 7f, 20f)
                horizontalLineTo(17f)
                curveTo(18.4f, 20f, 19.5f, 18.9f, 19.5f, 17.5f)
                verticalLineTo(14.5f)
            }
        }
    }

    val Share by lazy {
        icon("Share") {
            stroke {
                circle(17.5f, 6f, 2.5f)
                circle(6.5f, 12f, 2.5f)
                circle(17.5f, 18f, 2.5f)
                moveTo(8.7f, 10.8f); lineTo(15.3f, 7.2f)
                moveTo(8.7f, 13.2f); lineTo(15.3f, 16.8f)
            }
        }
    }

    val Magnet by lazy {
        icon("Magnet") {
            stroke {
                moveTo(6f, 4f); horizontalLineTo(10f); verticalLineTo(12f)
                arcTo(2f, 2f, 0f, false, false, 14f, 12f)
                verticalLineTo(4f); horizontalLineTo(18f); verticalLineTo(12f)
                arcTo(6f, 6f, 0f, false, true, 6f, 12f)
                close()
                moveTo(6f, 8f); horizontalLineTo(10f)
                moveTo(14f, 8f); horizontalLineTo(18f)
            }
        }
    }

    val ZoomIn by lazy {
        icon("ZoomIn") {
            stroke {
                circle(10.5f, 10.5f, 6.5f)
                moveTo(15.5f, 15.5f); lineTo(20f, 20f)
                moveTo(10.5f, 7.5f); verticalLineTo(13.5f)
                moveTo(7.5f, 10.5f); horizontalLineTo(13.5f)
            }
        }
    }

    val ZoomOut by lazy {
        icon("ZoomOut") {
            stroke {
                circle(10.5f, 10.5f, 6.5f)
                moveTo(15.5f, 15.5f); lineTo(20f, 20f)
                moveTo(7.5f, 10.5f); horizontalLineTo(13.5f)
            }
        }
    }

    val Fullscreen by lazy {
        icon("Fullscreen") {
            stroke {
                moveTo(4f, 9f); verticalLineTo(4f); horizontalLineTo(9f)
                moveTo(15f, 4f); horizontalLineTo(20f); verticalLineTo(9f)
                moveTo(20f, 15f); verticalLineTo(20f); horizontalLineTo(15f)
                moveTo(9f, 20f); horizontalLineTo(4f); verticalLineTo(15f)
            }
        }
    }

    val Focus by lazy {
        icon("Focus") {
            stroke {
                roundRect(3f, 4f, 18f, 16f, 2.5f)
                moveTo(8.5f, 4f); verticalLineTo(20f)
                moveTo(15.5f, 4f); verticalLineTo(20f)
            }
        }
    }

    val Volume by lazy {
        icon("Volume") {
            stroke {
                moveTo(4f, 9.5f); verticalLineTo(14.5f); horizontalLineTo(7.5f); lineTo(12f, 18.5f); verticalLineTo(5.5f); lineTo(7.5f, 9.5f); close()
                moveTo(15.5f, 9f); curveTo(17f, 10.6f, 17f, 13.4f, 15.5f, 15f)
                moveTo(18.2f, 6.5f); curveTo(21f, 9.5f, 21f, 14.5f, 18.2f, 17.5f)
            }
        }
    }

    val Mute by lazy {
        icon("Mute") {
            stroke {
                moveTo(4f, 9.5f); verticalLineTo(14.5f); horizontalLineTo(7.5f); lineTo(12f, 18.5f); verticalLineTo(5.5f); lineTo(7.5f, 9.5f); close()
                moveTo(16f, 9.5f); lineTo(21f, 14.5f)
                moveTo(21f, 9.5f); lineTo(16f, 14.5f)
            }
        }
    }

    val Lock by lazy {
        icon("Lock") {
            stroke {
                roundRect(5f, 10.5f, 14f, 10f, 2f)
                moveTo(8f, 10.5f); verticalLineTo(7.5f)
                arcTo(4f, 4f, 0f, false, true, 16f, 7.5f)
                verticalLineTo(10.5f)
            }
        }
    }

    val Unlock by lazy {
        icon("Unlock") {
            stroke {
                roundRect(5f, 10.5f, 14f, 10f, 2f)
                moveTo(8f, 10.5f); verticalLineTo(7.5f)
                arcTo(4f, 4f, 0f, false, true, 15.6f, 5.8f)
            }
        }
    }

    val Sliders by lazy {
        icon("Sliders") {
            stroke {
                moveTo(4f, 7f); horizontalLineTo(13f); moveTo(17f, 7f); horizontalLineTo(20f)
                circle(15f, 7f, 2f)
                moveTo(4f, 17f); horizontalLineTo(7f); moveTo(11f, 17f); horizontalLineTo(20f)
                circle(9f, 17f, 2f)
            }
        }
    }

    val Copy by lazy {
        icon("Copy") {
            stroke {
                roundRect(8.5f, 8.5f, 11.5f, 11.5f, 2f)
                moveTo(15.5f, 8.5f); verticalLineTo(6f)
                curveTo(15.5f, 4.9f, 14.6f, 4f, 13.5f, 4f)
                horizontalLineTo(6f)
                curveTo(4.9f, 4f, 4f, 4.9f, 4f, 6f)
                verticalLineTo(13.5f)
                curveTo(4f, 14.6f, 4.9f, 15.5f, 6f, 15.5f)
                horizontalLineTo(8.5f)
            }
        }
    }

    val Edit by lazy {
        icon("Edit") {
            stroke {
                moveTo(4f, 20f); horizontalLineTo(8f); lineTo(19f, 9f)
                curveTo(20.1f, 7.9f, 20.1f, 6.1f, 19f, 5f)
                curveTo(17.9f, 3.9f, 16.1f, 3.9f, 15f, 5f)
                lineTo(4f, 16f); close()
                moveTo(13.5f, 6.5f); lineTo(17.5f, 10.5f)
            }
        }
    }

    val More by lazy {
        icon("More") {
            filled { circle(6f, 12f, 1.6f) }
            filled { circle(12f, 12f, 1.6f) }
            filled { circle(18f, 12f, 1.6f) }
        }
    }

    val Search by lazy {
        icon("Search") { stroke { circle(10.5f, 10.5f, 6.5f); moveTo(15.5f, 15.5f); lineTo(20f, 20f) } }
    }

    val Transform by lazy {
        icon("Transform") {
            stroke {
                roundRect(6f, 6f, 12f, 12f, 1.5f)
                moveTo(3f, 3f); lineTo(6f, 6f)
                moveTo(21f, 21f); lineTo(18f, 18f)
                moveTo(3f, 6.5f); verticalLineTo(3f); horizontalLineTo(6.5f)
                moveTo(21f, 17.5f); verticalLineTo(21f); horizontalLineTo(17.5f)
            }
        }
    }

    val Crop by lazy {
        icon("Crop") {
            stroke {
                moveTo(7f, 3f); verticalLineTo(17f); horizontalLineTo(21f)
                moveTo(3f, 7f); horizontalLineTo(17f); verticalLineTo(21f)
            }
        }
    }

    val Speed by lazy {
        icon("Speed") {
            stroke {
                moveTo(4.5f, 16.5f)
                arcTo(8f, 8f, 0f, true, true, 19.5f, 16.5f)
                moveTo(12f, 13.5f); lineTo(16f, 9f)
                circle(12f, 14f, 1.2f)
            }
        }
    }

    val Opacity by lazy {
        icon("Opacity") {
            stroke { circle(12f, 12f, 8f) }
            filled {
                moveTo(12f, 4f)
                arcTo(8f, 8f, 0f, false, true, 12f, 20f)
                close()
            }
        }
    }

    val Fade by lazy {
        icon("Fade") {
            stroke {
                moveTo(3f, 19f); lineTo(9f, 6f); horizontalLineTo(15f); lineTo(21f, 19f)
                moveTo(3f, 19f); horizontalLineTo(21f)
            }
        }
    }

    val Folder by lazy {
        icon("Folder") {
            stroke {
                moveTo(3.5f, 7f)
                curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
                horizontalLineTo(9.5f); lineTo(11.5f, 7.5f); horizontalLineTo(18.5f)
                curveTo(19.6f, 7.5f, 20.5f, 8.4f, 20.5f, 9.5f)
                verticalLineTo(17f)
                curveTo(20.5f, 18.1f, 19.6f, 19f, 18.5f, 19f)
                horizontalLineTo(5.5f)
                curveTo(4.4f, 19f, 3.5f, 18.1f, 3.5f, 17f)
                close()
            }
        }
    }

    val Warning by lazy {
        icon("Warning") {
            stroke {
                moveTo(12f, 4f); lineTo(21f, 19.5f); horizontalLineTo(3f); close()
                moveTo(12f, 10f); verticalLineTo(14f)
                moveTo(12f, 17f); verticalLineTo(17.01f)
            }
        }
    }

    val Layers by lazy {
        icon("Layers") {
            stroke {
                moveTo(12f, 4f); lineTo(20f, 8.5f); lineTo(12f, 13f); lineTo(4f, 8.5f); close()
                moveTo(4f, 12.5f); lineTo(12f, 17f); lineTo(20f, 12.5f)
                moveTo(4f, 16f); lineTo(12f, 20.5f); lineTo(20f, 16f)
            }
        }
    }

    val Link by lazy {
        icon("Link") {
            stroke {
                moveTo(10f, 14f); lineTo(14f, 10f)
                moveTo(8.5f, 11f); lineTo(6.6f, 12.9f)
                curveTo(5.1f, 14.4f, 5.1f, 16.9f, 6.6f, 18.4f)
                curveTo(8.1f, 19.9f, 10.6f, 19.9f, 12.1f, 18.4f)
                lineTo(13f, 17.5f)
                moveTo(15.5f, 13f); lineTo(17.4f, 11.1f)
                curveTo(18.9f, 9.6f, 18.9f, 7.1f, 17.4f, 5.6f)
                curveTo(15.9f, 4.1f, 13.4f, 4.1f, 11.9f, 5.6f)
                lineTo(11f, 6.5f)
            }
        }
    }

    val Keyframe by lazy {
        icon("Keyframe") { stroke { moveTo(12f, 4f); lineTo(20f, 12f); lineTo(12f, 20f); lineTo(4f, 12f); close() } }
    }

    val KeyframeFilled by lazy {
        icon("KeyframeFilled") { filled { moveTo(12f, 3.5f); lineTo(20.5f, 12f); lineTo(12f, 20.5f); lineTo(3.5f, 12f); close() } }
    }

    val ColorWheel by lazy {
        icon("ColorWheel") {
            stroke {
                circle(12f, 12f, 8.5f)
                moveTo(12f, 3.5f); verticalLineTo(20.5f)
            }
            filled {
                moveTo(12f, 3.5f)
                arcTo(8.5f, 8.5f, 0f, false, true, 12f, 20.5f)
                close()
            }
        }
    }

    val Sparkle by lazy {
        icon("Sparkle") {
            stroke {
                moveTo(12f, 3f); lineTo(13.8f, 10.2f); lineTo(21f, 12f); lineTo(13.8f, 13.8f); lineTo(12f, 21f)
                lineTo(10.2f, 13.8f); lineTo(3f, 12f); lineTo(10.2f, 10.2f); close()
            }
        }
    }

    val Font by lazy {
        icon("Font") {
            stroke {
                moveTo(4f, 19f); lineTo(9f, 5f); lineTo(14f, 19f)
                moveTo(5.8f, 14f); horizontalLineTo(12.2f)
                moveTo(16f, 11f); horizontalLineTo(21f)
                moveTo(18.5f, 8.5f); verticalLineTo(19f)
            }
        }
    }

    val Freeze by lazy {
        icon("Freeze") {
            stroke {
                moveTo(12f, 3f); verticalLineTo(21f)
                moveTo(4.2f, 7.5f); lineTo(19.8f, 16.5f)
                moveTo(19.8f, 7.5f); lineTo(4.2f, 16.5f)
                moveTo(9.5f, 4.5f); lineTo(12f, 6.5f); lineTo(14.5f, 4.5f)
                moveTo(9.5f, 19.5f); lineTo(12f, 17.5f); lineTo(14.5f, 19.5f)
            }
        }
    }

    // --------------------------------------------------------------------------------------

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Clippr.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        horizontalLineTo(x + w - r)
        arcTo(r, r, 0f, false, true, x + w, y + r)
        verticalLineTo(y + h - r)
        arcTo(r, r, 0f, false, true, x + w - r, y + h)
        horizontalLineTo(x + r)
        arcTo(r, r, 0f, false, true, x, y + h - r)
        verticalLineTo(y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    private const val STROKE = 1.75f
}
