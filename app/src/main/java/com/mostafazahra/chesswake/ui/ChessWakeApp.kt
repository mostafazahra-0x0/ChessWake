package com.mostafazahra.chesswake.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mostafazahra.chesswake.alarm.ui.AlarmEditScreen
import com.mostafazahra.chesswake.alarm.ui.AlarmsScreen
import com.mostafazahra.chesswake.puzzle.ui.PracticeScreen
import com.mostafazahra.chesswake.settings.ui.SettingsScreen
import com.mostafazahra.chesswake.settings.ui.SettingsViewModel
import com.mostafazahra.chesswake.stats.ui.StatsScreen
import com.mostafazahra.chesswake.ui.navigation.Routes
import com.mostafazahra.chesswake.ui.navigation.TopLevelDestination
import com.mostafazahra.chesswake.ui.theme.BoardColors
import com.mostafazahra.chesswake.ui.theme.ChessWakeTheme

/**
 * The root of the app UI: theme, bottom navigation and the nav graph.
 *
 * The theme is driven by DataStore through [SettingsViewModel], which is scoped to
 * the activity here so a settings change recolours the whole app immediately.
 */
@Composable
fun ChessWakeApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    ChessWakeTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
        boardColors = BoardColors(
            light = Color(settings.boardLightColor),
            dark = Color(settings.boardDarkColor),
        ),
    ) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
            ChessWakeScaffold(navController = navController)
        }
    }
}

@Composable
private fun ChessWakeScaffold(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = TopLevelDestination.fromRoute(backStackEntry?.destination?.route)

    Scaffold(
        bottomBar = {
            // The bar hides on the alarm editor, which needs the vertical room.
            AnimatedVisibility(
                visible = currentDestination != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = destination == currentDestination
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        ChessWakeNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun ChessWakeNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.ALARMS,
        modifier = modifier,
    ) {
        composable(Routes.ALARMS) {
            AlarmsScreen(
                onCreateAlarm = { navController.navigate(Routes.alarmEdit()) },
                onEditAlarm = { alarmId -> navController.navigate(Routes.alarmEdit(alarmId)) },
            )
        }

        composable(
            route = Routes.ALARM_EDIT,
            arguments = listOf(
                navArgument(Routes.ARG_ALARM_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NEW_ALARM_ID
                },
            ),
        ) { entry ->
            val alarmId = entry.arguments?.getLong(Routes.ARG_ALARM_ID) ?: Routes.NEW_ALARM_ID
            AlarmEditScreen(
                alarmId = alarmId,
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.PRACTICE) {
            PracticeScreen()
        }

        composable(Routes.STATS) {
            StatsScreen()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}

/**
 * Tab switching that behaves like the platform: one copy of each tab on the back
 * stack, position and scroll state restored, and no pile-up of duplicate entries.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
