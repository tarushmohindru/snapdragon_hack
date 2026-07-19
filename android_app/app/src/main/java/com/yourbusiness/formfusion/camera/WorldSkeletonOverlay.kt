package com.yourbusiness.formfusion.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.yourbusiness.formfusion.pose.Skeleton
import kotlin.math.max

/** Orthographic world-space view; joint radius encodes depth while x/y preserve geometry. */
@Composable
fun WorldSkeletonOverlay(
    joints: Map<Int, Triple<Float, Float, Float>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (joints.isEmpty()) return@Canvas
        val minX = joints.values.minOf { it.first }
        val maxX = joints.values.maxOf { it.first }
        val minY = joints.values.minOf { it.second }
        val maxY = joints.values.maxOf { it.second }
        val minZ = joints.values.minOf { it.third }
        val maxZ = joints.values.maxOf { it.third }
        val spanX = max(maxX - minX, 0.001f)
        val spanY = max(maxY - minY, 0.001f)
        val spanZ = max(maxZ - minZ, 0.001f)
        val scale = minOf(size.width * 0.72f / spanX, size.height * 0.72f / spanY)
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        fun point(value: Triple<Float, Float, Float>) = Offset(
            size.width / 2f + (value.first - centerX) * scale,
            size.height / 2f - (value.second - centerY) * scale
        )

        Skeleton.EDGES.forEach { (start, end) ->
            val from = joints[start] ?: return@forEach
            val to = joints[end] ?: return@forEach
            drawLine(
                color = Color(0xFF73EFD0).copy(alpha = 0.9f),
                start = point(from),
                end = point(to),
                strokeWidth = 5f
            )
        }
        joints.values.forEach { joint ->
            val depth = (joint.third - minZ) / spanZ
            drawCircle(
                color = Color(0xFFFF745D).copy(alpha = 0.65f + depth * 0.35f),
                radius = 6f + depth * 7f,
                center = point(joint)
            )
        }
    }
}
