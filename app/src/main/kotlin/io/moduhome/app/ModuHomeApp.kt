package io.moduhome.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.moduhome.app.core.model.ErrandKind
import io.moduhome.app.core.model.JourneyMode
import io.moduhome.app.feature.home.HomeScreen
import io.moduhome.app.feature.home.HomeUiState

@Composable
internal fun ModuHomeApp() {
    var mode by rememberSaveable { mutableStateOf(JourneyMode.TOGETHER) }
    var pickupSelected by rememberSaveable { mutableStateOf(false) }
    var groceriesSelected by rememberSaveable { mutableStateOf(false) }
    var additionalMinutes by rememberSaveable { mutableIntStateOf(30) }

    val selectedErrands = buildSet {
        if (pickupSelected) add(ErrandKind.PICKUP)
        if (groceriesSelected) add(ErrandKind.GROCERIES)
    }

    HomeScreen(
        state = HomeUiState(mode, selectedErrands, additionalMinutes),
        onModeSelected = { selected ->
            mode = selected
            if (selected == JourneyMode.TOGETHER && pickupSelected && groceriesSelected) {
                groceriesSelected = false
            }
        },
        onErrandToggled = { errand ->
            when (errand) {
                ErrandKind.PICKUP -> {
                    pickupSelected = !pickupSelected
                    if (mode == JourneyMode.TOGETHER && pickupSelected) groceriesSelected = false
                }
                ErrandKind.GROCERIES -> {
                    groceriesSelected = !groceriesSelected
                    if (mode == JourneyMode.TOGETHER && groceriesSelected) pickupSelected = false
                }
            }
        },
        onBudgetSelected = { additionalMinutes = it },
    )
}
