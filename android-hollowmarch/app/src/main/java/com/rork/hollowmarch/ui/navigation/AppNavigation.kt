package com.rork.hollowmarch.ui.navigation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rork.hollowmarch.game.GameViewModel
import com.rork.hollowmarch.ui.components.EngravedRule
import com.rork.hollowmarch.ui.components.MonoText
import com.rork.hollowmarch.ui.screens.ChronicleScreen
import com.rork.hollowmarch.ui.screens.PlayScreen
import com.rork.hollowmarch.ui.screens.TitleScreen
import com.rork.hollowmarch.ui.theme.EngravedTitle
import com.rork.hollowmarch.ui.theme.Ink

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val gameViewModel: GameViewModel = viewModel()
    val titleState by gameViewModel.title.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = "title"
    ) {
        composable("title") {
            if (titleState.isForging) {
                ForgingPlate()
            } else {
                TitleScreen(
                    state = titleState,
                    classes = gameViewModel.classRoster.classes,
                    spawnSites = gameViewModel.spawnSites,
                    onContinue = {
                        gameViewModel.startExpedition(resume = true)
                        navController.navigate("play")
                    },
                    onForgeDelver = { creation ->
                        gameViewModel.startExpedition(resume = false, creation = creation)
                        navController.navigate("play")
                    },
                    onForgeWorld = { gameViewModel.forgeNewWorld(it) },
                    onChronicle = { navController.navigate("chronicle") }
                )
            }
        }
        composable("play") {
            PlayScreen(
                viewModel = gameViewModel,
                onLeave = { navController.popBackStack("title", inclusive = false) }
            )
        }
        composable("chronicle") {
            ChronicleScreen(
                viewModel = gameViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/** While the forge burns, the title keeps its peace: an ember pulses where the numbers will sit. */
@Composable
private fun ForgingPlate() {
    val ember = rememberInfiniteTransition(label = "forge")
    val glow by ember.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "forge"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Canvas),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EngravedRule(Modifier.padding(bottom = 10.dp))
            Text(
                text = "HOLLOWMARCH",
                style = EngravedTitle,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            EngravedRule(Modifier.padding(vertical = 10.dp))
            MonoText(
                text = "the forge is at work — burning the centuries…",
                color = Ink.Parchment.copy(alpha = glow),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
