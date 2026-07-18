package dev.blazelight.p4oc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.notification.NotificationRoute
import dev.blazelight.p4oc.core.notification.NotificationRouteCodec
import dev.blazelight.p4oc.ui.navigation.NavGraph
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.PocketCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val settingsDataStore: SettingsDataStore by inject()
    private val pendingNotificationRoute = MutableStateFlow<NotificationRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingNotificationRoute.value = NotificationRouteCodec.read(intent)

        setContent {
            val themeMode by settingsDataStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val themeName by settingsDataStore.themeName.collectAsStateWithLifecycle(
                initialValue = SettingsDataStore.DEFAULT_THEME_NAME
            )
            val oledBlack by settingsDataStore.oledBlack.collectAsStateWithLifecycle(initialValue = false)
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            PocketCodeTheme(themeName = themeName, darkTheme = darkTheme, oledBlack = oledBlack) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalOpenCodeTheme.current.background
                ) {
                    // Use NavGraph for initial Server/Setup screens,
                    // then MainTabScreen takes over after connection
                    val navController = rememberNavController()
                    NavGraph(
                        navController = navController,
                        startDestination = Screen.Server.route,
                        pendingNotificationRoute = pendingNotificationRoute,
                        onNotificationRouteConsumed = { route ->
                            if (pendingNotificationRoute.compareAndSet(route, null)) {
                                NotificationRouteCodec.clear(intent)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationRouteCodec.read(intent)?.let { pendingNotificationRoute.value = it }
    }
}
