package vibe.tube.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Material 3 Expressive building blocks used across the app. Keeping them in one file makes
 * the expressive API surface (wavy progress, connected button groups) easy to audit and swap.
 */

/** Determinate linear progress drawn as the expressive wavy indicator. */
@Composable
fun VibeLinearProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(progress = progress, modifier = modifier)
}

/** Indeterminate linear progress drawn as the expressive wavy indicator. */
@Composable
fun VibeLinearProgress(modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(modifier = modifier)
}

/** Determinate circular progress drawn as the expressive wavy indicator. */
@Composable
fun VibeCircularProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    CircularWavyProgressIndicator(progress = progress, modifier = modifier)
}

/** Indeterminate circular progress drawn as the expressive wavy indicator. */
@Composable
fun VibeCircularProgress(modifier: Modifier = Modifier) {
    CircularWavyProgressIndicator(modifier = modifier)
}

/**
 * Single-choice connected button group (M3 Expressive): a row of [ToggleButton]s with shared,
 * morphing corners. [label] must return the visible text for an option.
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (it) onSelect(option) },
                modifier = Modifier.semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(label(option))
            }
        }
    }
}
