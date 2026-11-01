package vibe.tube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vibe.tube.ui.theme.LocalReducedMotion

@Composable
fun MorphingSymbol(
    @DrawableRes outline: Int,
    @DrawableRes filled: Int,
    fill: () -> Float,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val from = ImageVector.vectorResource(outline)
    val clean = ImageVector.vectorResource(filled)
    val targetId = vibe.tube.ui.VIcon.fillMorphTarget(filled)
    val target = if (targetId != null) ImageVector.vectorResource(targetId) else null
    val morph = remember(from, target) { target?.let { SymbolMorph(from, it) }?.takeIf { it.isMorphable } }
    val color = if (tint == Color.Unspecified) LocalContentColor.current else tint
    val semantics = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    androidx.compose.foundation.layout.Spacer(
        modifier
            .then(semantics)
            .size(24.dp)
            .drawWithCache {
                val outlinePath = SymbolMorph.pathOf(from)
                val cleanPath = SymbolMorph.pathOf(clean)
                val work = Path()
                val layer = androidx.compose.ui.graphics.Paint()
                val sx = size.width / from.viewportWidth
                val sy = size.height / from.viewportHeight
                val tx = SymbolMorph.translateXOf(from)
                val ty = SymbolMorph.translateYOf(from)
                onDrawBehind {
                    val f = fill().coerceIn(0f, 1f)
                    scale(sx, sy, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        translate(tx, ty) {
                            when {
                                f <= 0.001f -> drawPath(outlinePath, color)
                                f >= 0.999f -> drawPath(cleanPath, color)
                                else -> {
                                    
                                    val swept = morph?.path(f, work) ?: outlinePath
                                    val k = if (morph != null) ((f - (1f - HANDOFF)) / HANDOFF).coerceIn(0f, 1f) else f
                                    if (k <= 0f) {
                                        drawPath(swept, color)
                                    } else {
                                        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, -from.viewportHeight, from.viewportWidth, from.viewportHeight), layer)
                                        drawPath(swept, color, alpha = 1f - k)
                                        drawPath(cleanPath, color, alpha = k, blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
                                        drawContext.canvas.restore()
                                    }
                                }
                            }
                        }
                    }
                }
            },
    )
}

private const val HANDOFF = 0.2f

@Composable
fun SelectableSymbol(
    @DrawableRes outline: Int,
    @DrawableRes filled: Int,
    selected: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (outline == vibe.tube.ui.VIcon.Shorts) {
        androidx.compose.animation.Crossfade(selected, animationSpec = androidx.compose.animation.core.tween(if (LocalReducedMotion.current) 0 else 160), label = "shortsSelection") {
            VIconImage(if (it) filled else outline, contentDescription, modifier, tint)
        }
        return
    }
    val reduced = LocalReducedMotion.current
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (reduced) androidx.compose.animation.core.snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "symbolFill",
    )
    MorphingSymbol(outline, filled, { fill }, contentDescription, modifier, tint)
}

@Immutable
internal class SymbolMorph(from: ImageVector, to: ImageVector) {
    private val a: List<PathNode> = from.onlyPath()
    private val b: List<PathNode> = to.onlyPath()
    private val compatible: Boolean = a.size == b.size && a.indices.all { a[it]::class == b[it]::class && a[it].isSupported() }

    val isMorphable: Boolean get() = compatible

    fun path(t: Float, into: Path): Path {
        into.rewind()
        if (!compatible) {
            
            addNodes(if (t < 0.5f) a else b, into)
            return into
        }
        for (i in a.indices) {
            when (val n = a[i]) {
                is PathNode.MoveTo -> { val m = b[i] as PathNode.MoveTo; into.moveTo(mix(n.x, m.x, t), mix(n.y, m.y, t)) }
                is PathNode.LineTo -> { val m = b[i] as PathNode.LineTo; into.lineTo(mix(n.x, m.x, t), mix(n.y, m.y, t)) }
                is PathNode.QuadTo -> {
                    val m = b[i] as PathNode.QuadTo
                    into.quadraticTo(mix(n.x1, m.x1, t), mix(n.y1, m.y1, t), mix(n.x2, m.x2, t), mix(n.y2, m.y2, t))
                }
                is PathNode.CurveTo -> {
                    val m = b[i] as PathNode.CurveTo
                    into.cubicTo(
                        mix(n.x1, m.x1, t), mix(n.y1, m.y1, t), mix(n.x2, m.x2, t), mix(n.y2, m.y2, t),
                        mix(n.x3, m.x3, t), mix(n.y3, m.y3, t),
                    )
                }
                PathNode.Close -> into.close()
                else -> Unit
            }
        }
        return into
    }

    private fun addNodes(nodes: List<PathNode>, into: Path) {
        nodes.toPath(into)
    }

    companion object {
        fun pathOf(vector: ImageVector): Path = vector.onlyPath().toPath(Path())

        fun translateXOf(vector: ImageVector): Float = vector.root.firstGroup()?.translationX ?: 0f

        fun translateYOf(vector: ImageVector): Float = vector.root.firstGroup()?.translationY ?: 0f

        private fun mix(x: Float, y: Float, t: Float) = x + (y - x) * t

        private fun PathNode.isSupported() = this is PathNode.MoveTo || this is PathNode.LineTo || this is PathNode.QuadTo ||
            this is PathNode.CurveTo || this == PathNode.Close

        private fun VectorGroup.firstGroup(): VectorGroup? = asSequence().filterIsInstance<VectorGroup>().firstOrNull()

        private fun ImageVector.onlyPath(): List<PathNode> {
            fun collect(group: VectorGroup): List<PathNode> = group.asSequence().flatMap { node ->
                when (node) {
                    is VectorPath -> node.pathData.asSequence()
                    is VectorGroup -> collect(node).asSequence()
                }
            }.toList()
            return collect(root)
        }
    }
}
