package io.moduhome.app.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.moduhome.app.R
import io.moduhome.app.core.model.ErrandKind
import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.ui.theme.ModuHomeTheme

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onModeSelected: (JourneyMode) -> Unit,
    onErrandToggled: (ErrandKind) -> Unit,
    onBudgetSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .widthIn(max = 960.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.home_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = state.mode == JourneyMode.TOGETHER,
                        onClick = { onModeSelected(JourneyMode.TOGETHER) },
                        label = { Text(stringResource(R.string.mode_together)) },
                    )
                    FilterChip(
                        selected = state.mode == JourneyMode.SOLO,
                        onClick = { onModeSelected(JourneyMode.SOLO) },
                        label = { Text(stringResource(R.string.mode_solo)) },
                    )
                }
                Text(
                    text = stringResource(
                        if (state.mode == JourneyMode.TOGETHER) R.string.together_scope else R.string.solo_scope,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.errands_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.selection_count, state.selectedErrands.size, state.mode.maxErrands),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                ErrandRow(
                    label = R.string.errand_pickup,
                    stayMinutes = 5,
                    checked = ErrandKind.PICKUP in state.selectedErrands,
                    onChecked = { onErrandToggled(ErrandKind.PICKUP) },
                )
                ErrandRow(
                    label = R.string.errand_groceries,
                    stayMinutes = 15,
                    checked = ErrandKind.GROCERIES in state.selectedErrands,
                    onChecked = { onErrandToggled(ErrandKind.GROCERIES) },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.budget_title), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(10, 20, 30).forEach { minutes ->
                        FilterChip(
                            selected = state.allowedAdditionalMinutes == minutes,
                            onClick = { onBudgetSelected(minutes) },
                            label = { Text(stringResource(R.string.minutes, minutes)) },
                        )
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.preview_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TimeValue(R.string.driving_extra, state.demoExtraDrivingMinutes)
                        TimeValue(R.string.stay_time, state.demoStayMinutes)
                        TimeValue(R.string.total_extra, state.demoBudgetResult.additionalMinutes)
                    }
                    Text(
                        stringResource(
                            if (state.demoBudgetResult.isWithinBudget) R.string.budget_within else R.string.budget_exceeded,
                        ),
                        color = if (state.demoBudgetResult.isWithinBudget) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrandRow(
    @StringRes label: Int,
    stayMinutes: Int,
    checked: Boolean,
    onChecked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onChecked() }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.errand_stay, stayMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeValue(@StringRes label: Int, minutes: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.minutes, minutes), style = MaterialTheme.typography.titleLarge)
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 840)
@Composable
private fun HomeScreenPreview() {
    ModuHomeTheme {
        HomeScreen(
            state = HomeUiState(
                mode = JourneyMode.SOLO,
                selectedErrands = setOf(ErrandKind.PICKUP, ErrandKind.GROCERIES),
            ),
            onModeSelected = {},
            onErrandToggled = {},
            onBudgetSelected = {},
        )
    }
}
