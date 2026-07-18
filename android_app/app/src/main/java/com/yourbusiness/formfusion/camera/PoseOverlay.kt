package com.yourbusiness.formfusion.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yourbusiness.formfusion.pose.PersonPose
import com.yourbusiness.formfusion.pose.Skeleton
import kotlin.math.max
import kotlin.math.min

private val DOT_COLOR = Color(0xFFFFEB3B)
private val LINE_COLOR = Color(0xFF00E676)

/**
 * Draws each detected person's body keypoints as dots connected into a skeleton, over the
 * camera preview.
 *
 * Coordinate pipeline this assumes upstream (see PoseAnalyzer.analyze):
 * 1. [imageWidth]/[imageHeight] are already cropped to imageProxy.cropRect (the ViewPort
 *    region PreviewView actually shows) and rotated upright by rotationDegrees — so this
 *    function only has to handle the remaining preview-vs-view scale difference.
 * 2. [scaleType] must match whatever PreviewView.scaleType is actually set to (see
 *    PREVIEW_SCALE_TYPE) — both FILL_* (cover, crops overflow) and FIT_* (contain,
 *    letterboxes) are handled; anything else falls back to FILL_CENTER-style math.
 * 3. [mirrorHorizontally] should be true iff the bound CameraSelector is front-facing —
 *    PreviewView mirrors the front camera's feed but the raw analysis buffer isn't mirrored.
 * 4. Compose's Canvas DrawScope and the source Bitmap/ImageProxy both use a top-left
 *    origin with x-right/y-down, so no origin correction is needed between them.
 */
@Composable
fun PoseOverlay(
    persons: List<PersonPose>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    scaleType: PreviewView.ScaleType = PREVIEW_SCALE_TYPE,
    mirrorHorizontally: Boolean = false
) {
    if (imageWidth <= 0 || imageHeight <= 0) return

    val dotRadiusPx = with(LocalDensity.current) { 5.dp.toPx() }
    val lineWidthPx = with(LocalDensity.current) { 3.dp.toPx() }
    val isFillType = scaleType == PreviewView.ScaleType.FILL_CENTER ||
        scaleType == PreviewView.ScaleType.FILL_START ||
        scaleType == PreviewView.ScaleType.FILL_END

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        // FILL_* (cover): scale up until there's no gap on either axis, cropping the
        // overflow. FIT_* (contain): scale up until it just touches the nearer axis,
        // letterboxing the rest. Only the *_CENTER alignment is implemented — this app
        // never uses a START/END variant, so both fall back to a centered offset.
        val scale = if (isFillType) {
            max(size.width / imageWidth, size.height / imageHeight)
        } else {
            min(size.width / imageWidth, size.height / imageHeight)
        }
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

        fun mapPoint(x: Float, y: Float): Offset {
            val srcX = if (mirrorHorizontally) imageWidth - x else x
            return Offset(srcX * scale + offsetX, y * scale + offsetY)
        }

        persons.forEach { person ->
            val byId = person.landmarks.associateBy { it.id }

            // Connect the joints into a skeleton first (drawn under the dots).
            Skeleton.EDGES.forEach { (a, b) ->
                val pa = byId[a]
                val pb = byId[b]
                if (pa != null && pb != null) {
                    drawLine(
                        color = LINE_COLOR,
                        start = mapPoint(pa.x, pa.y),
                        end = mapPoint(pb.x, pb.y),
                        strokeWidth = lineWidthPx
                    )
                }
            }

            byId.values.forEach { point ->
                if (point.id <= Skeleton.MAX_DRAWN_KEYPOINT_ID) {
                    drawCircle(
                        color = DOT_COLOR,
                        radius = dotRadiusPx,
                        center = mapPoint(point.x, point.y)
                    )
                }
            }
        }
    }
}
