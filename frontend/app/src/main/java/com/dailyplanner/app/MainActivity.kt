package com.dailyplanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dailyplanner.app.ui.editor.EditorScreen
import com.dailyplanner.app.ui.plans.PlanDetailScreen
import com.dailyplanner.app.ui.plans.PlansScreen
import com.dailyplanner.app.ui.reminders.RemindersScreen
import com.dailyplanner.app.ui.templates.TemplatesScreen
import com.dailyplanner.app.ui.theme.AppAssets
import com.dailyplanner.app.ui.theme.DailyPlannerTheme

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Templates("templates", "Templates", Icons.Outlined.Dashboard),
    Reminders("reminders", "Reminders", Icons.Outlined.NotificationsNone),
    Plans("plans", "My Plans", Icons.Outlined.EventNote),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppAssets.manager = assets
        enableEdgeToEdge()
        setContent { DailyPlannerTheme { App() } }
        debugAlarm(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        debugAlarm(intent)
    }

    /**
     * Debug builds only: `adb shell am start -n <pkg>/com.dailyplanner.app.MainActivity --ei debug_alarm_in 60`
     * creates a real reminder (same save + AlarmManager path as the UI) to test alarms end to end.
     */
    private fun debugAlarm(intent: android.content.Intent?) {
        val secs = intent?.getIntExtra("debug_alarm_in", -1) ?: -1
        if (!BuildConfig.DEBUG || secs < 0) return
        intent?.removeExtra("debug_alarm_in")
        val c = (application as DailyPlannerApp).container
        lifecycleScope.launch {
            val at = java.time.OffsetDateTime.now().plusSeconds(secs.toLong()).withNano(0)
            val r = c.repository.saveReminder(
                com.dailyplanner.app.data.model.ReminderInput(
                    title = intent?.getStringExtra("debug_alarm_title") ?: "Test alarm",
                    note = "Debug test",
                    remindAt = at.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                ),
            )
            c.scheduler.schedule(r)
            android.util.Log.i("DailyPlannerAlarm", "scheduled ${r.id} at ${r.remindAt} exact=${c.scheduler.canRingOnTime()}")
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun App() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }

    Scaffold(
        bottomBar = {
            if (tab != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { nav.switchTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Templates.route, modifier = Modifier.padding(if (tab != null) padding else androidx.compose.foundation.layout.PaddingValues())) {
            composable(Tab.Templates.route) {
                TemplatesScreen(onOpen = { nav.navigate("editor?templateId=${it.id}") })
            }
            composable(Tab.Reminders.route) { RemindersScreen() }
            composable(Tab.Plans.route) {
                PlansScreen(
                    onOpen = { nav.navigate("plan/${it.id}") },
                    onBrowseTemplates = { nav.switchTab(Tab.Templates.route) },
                )
            }
            composable(
                "editor?templateId={templateId}&planId={planId}",
                arguments = listOf(
                    navArgument("templateId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("planId") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e ->
                EditorScreen(
                    templateId = e.arguments?.getString("templateId"),
                    planId = e.arguments?.getString("planId"),
                    onBack = { nav.popBackStack() },
                    onSaved = { planId, isNew ->
                        if (isNew) {
                            // New plan: land on My Plans with the saved plan open.
                            nav.switchTab(Tab.Plans.route)
                            nav.navigate("plan/$planId")
                        } else {
                            nav.popBackStack()
                        }
                    },
                )
            }
            composable("plan/{planId}") { e ->
                val id = e.arguments!!.getString("planId")!!
                PlanDetailScreen(
                    planId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("editor?planId=$it") },
                )
            }
        }
    }
}
