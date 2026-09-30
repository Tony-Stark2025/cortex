package com.cortex.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.cortex.app.ui.CortexViewModel
import com.cortex.app.ui.screens.ChatScreen
import com.cortex.app.ui.screens.PaywallScreen
import com.cortex.app.ui.screens.ProjectListScreen
import com.cortex.app.ui.theme.CortexTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class ScreenState {
    CHAT,
    PROJECTS,
    PAYWALL
}

enum class FoldPosture {
    NORMAL,
    TABLETOP_HORIZONTAL,
    BOOK_VERTICAL
}

class MainActivity : ComponentActivity() {

    private val viewModel: CortexViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var foldPosture by mutableStateOf(FoldPosture.NORMAL)

        // Listen for Samsung Galaxy Foldable hinge posture & orientation changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collectLatest { layoutInfo ->
                        val foldingFeature = layoutInfo.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstOrNull()

                        foldPosture = when {
                            foldingFeature?.state == FoldingFeature.State.HALF_OPENED &&
                                foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL ->
                                FoldPosture.BOOK_VERTICAL
                            foldingFeature?.state == FoldingFeature.State.HALF_OPENED ->
                                FoldPosture.TABLETOP_HORIZONTAL
                            else ->
                                FoldPosture.NORMAL
                        }
                    }
            }
        }

        setContent {
            CortexTheme {
                var currentScreen by rememberSaveable { mutableStateOf(ScreenState.CHAT) }

                BackHandler(enabled = currentScreen != ScreenState.CHAT) {
                    currentScreen = ScreenState.CHAT
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                        when (screen) {
                            ScreenState.CHAT -> {
                                ChatScreen(
                                    repository = viewModel.repository,
                                    aiService = viewModel.aiService,
                                    onOpenProjects = { currentScreen = ScreenState.PROJECTS },
                                    onOpenPaywall = { currentScreen = ScreenState.PAYWALL },
                                    isFlexMode = foldPosture != FoldPosture.NORMAL,
                                    foldPosture = foldPosture,
                                    viewModel = viewModel
                                )
                            }
                            ScreenState.PROJECTS -> {
                                ProjectListScreen(
                                    repository = viewModel.repository,
                                    onProjectSelected = { currentScreen = ScreenState.CHAT },
                                    onBack = { currentScreen = ScreenState.CHAT },
                                    onOpenPaywall = { currentScreen = ScreenState.PAYWALL }
                                )
                            }
                            ScreenState.PAYWALL -> {
                                PaywallScreen(
                                    onDismiss = { currentScreen = ScreenState.CHAT }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
