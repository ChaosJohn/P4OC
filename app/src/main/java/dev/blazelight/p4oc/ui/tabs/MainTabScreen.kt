@file:Suppress("DEPRECATION") // LocalLifecycleOwner – platform version until lifecycle-runtime-compose upgrade

package dev.blazelight.p4oc.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.data.remote.dto.CreatePtyRequest
import dev.blazelight.p4oc.data.session.SessionRepositoryProvider
import dev.blazelight.p4oc.data.session.presence
import dev.blazelight.p4oc.domain.model.SessionConnectionState
import dev.blazelight.p4oc.domain.model.SessionStatus
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.domain.workspace.Workspace
import dev.blazelight.p4oc.ui.components.TuiAlertDialog
import dev.blazelight.p4oc.ui.components.TuiTextButton
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.screens.home.HomeActions
import dev.blazelight.p4oc.ui.screens.home.HomeSummaryBuilder
import dev.blazelight.p4oc.ui.screens.home.homeScreen
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiShapes
import dev.blazelight.p4oc.ui.workspace.WorkspaceRepositoryOwner
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val TAG = "MainTabScreen"

/**
 * Main container for the tab-based UI.
 * Shows TabBar at top and active tab's content below.
 */
@Composable
fun MainTabScreen(
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabManager: TabManager = koinInject()
    val connectionManager: ConnectionManager = koinInject()
    val settingsDataStore: SettingsDataStore = koinInject()
    val sessionRepositoryProvider: SessionRepositoryProvider = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val theme = LocalOpenCodeTheme.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()
    val showTabWarning by tabManager.showTabWarning.collectAsState()
    val connectionState by connectionManager.connectionState.collectAsState()
    val currentServerRef = remember(connectionManager.currentBaseUrl) {
        connectionManager.currentBaseUrl?.let { ServerRef.fromEndpoint(it) }
    }
    val savedServers by settingsDataStore.savedServers.collectAsState(initial = emptyList())
    val homeConnectionStates = remember(savedServers, connectionState, currentServerRef?.endpointKey) {
        savedServers.associate { savedServer ->
            savedServer.endpointKey to if (savedServer.endpointKey == currentServerRef?.endpointKey) {
                connectionState
            } else {
                ConnectionState.Disconnected
            }
        }
    }

    var wasEverConnected by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        tabManager.ensureHomeTab(focus = false)
    }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var showStartWorkSheet by remember { mutableStateOf(false) }
    var showFilesTabPrompt by remember { mutableStateOf(false) }
    var homeDetailSelection by remember {
        mutableStateOf<StartWorkSelection>(StartWorkSelection.NeedsSelection)
    }

    // Foreground resume is delegated to ConnectionManager so reconnect policy
    // has one owner instead of competing UI timers and SSE retry callbacks.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            connectionManager.onAppForegrounded()
        }
    }

    // ConnectionManager owns SSE retry timeout and escalation. The UI only
    // reacts to terminal disconnected states after a successful connection.
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            wasEverConnected = true
            return@LaunchedEffect
        }
        if (!wasEverConnected) return@LaunchedEffect
        if (connectionState is ConnectionState.Disconnected && tabs.isNotEmpty()) {
            connectionManager.disconnect()
            onDisconnect()
        }
    }

    LaunchedEffect(connectionManager.currentBaseUrl) {
        val baseUrl = connectionManager.currentBaseUrl ?: return@LaunchedEffect
        if (tabManager.shouldAttemptRestore()) {
            val persisted = settingsDataStore.getPersistedTabState()
            if (persisted != null) {
                when (val result = tabManager.restoreState(persisted, ServerRef.fromEndpoint(baseUrl))) {
                    is RestoreResult.Restored -> AppLog.d(TAG, "Restored ${result.count} tabs")
                    RestoreResult.Empty -> AppLog.w(TAG, "Persisted tab state was empty")
                    is RestoreResult.VersionMismatch -> {
                        restoreError = "Saved tabs use unsupported version ${result.version}. Starting fresh."
                    }
                    is RestoreResult.ServerMismatch -> {
                        restoreError = "Saved tabs belong to ${result.persistedEndpointKey}, not ${result.activeEndpointKey}. Starting fresh."
                    }
                    is RestoreResult.MissingServer -> {
                        restoreError = "Saved tabs reference unavailable server ${result.endpointKey}. Starting fresh."
                    }
                }
            }
            if (!tabManager.hasTabs()) {
                tabManager.ensureHomeTab(focus = true)
            }
        }
    }

    LaunchedEffect(tabs, activeTabId, connectionManager.currentBaseUrl) {
        val baseUrl = connectionManager.currentBaseUrl ?: return@LaunchedEffect
        val state = tabManager.saveState(ServerRef.fromEndpoint(baseUrl)) ?: return@LaunchedEffect
        settingsDataStore.setPersistedTabState(state)
    }

    val tabTitleLabels = rememberTabTitleLabels()
    // Build tab titles and icons from current routes (updated inside pager pages).
    // Seed from startRoute so titles are correct even when pages are off-screen.
    val tabTitles = remember { mutableStateMapOf<String, String>() }
    val tabIcons = remember { mutableStateMapOf<String, ImageVector>() }
    tabs.forEach { tab ->
        if (tab.id !in tabTitles) {
            tabTitles[tab.id] = getTitleForRoute(
                route = tab.startRoute,
                labels = tabTitleLabels,
                sessionTitle = tab.sessionTitle,
                workspaceKey = tab.workspaceKey,
            )
            tabIcons[tab.id] = getIconForRoute(tab.startRoute)
        }
    }
    val tabConnectionStates = remember { mutableStateMapOf<String, SessionConnectionState>() }
    val tabReadTokens = remember { mutableStateMapOf<String, Long>() }
    // Track current routes per tab (for PTY cleanup on tab close)
    val tabRoutes = remember { mutableStateMapOf<String, String>() }
    val tabPtyIds = remember { mutableStateMapOf<String, String>() }
    val connection = connectionManager.connection.collectAsState().value
    val baseUrl = connection?.config?.url
    val generation = connection?.generation
    val workspaceOwners = remember { mutableStateMapOf<String, WorkspaceRepositoryOwner>() }

    DisposableEffect(Unit) {
        onDispose {
            workspaceOwners.values.forEach { it.close() }
            workspaceOwners.clear()
        }
    }

    LaunchedEffect(tabs, baseUrl, generation) {
        if (baseUrl == null || generation == null) {
            workspaceOwners.values.forEach { it.close() }
            workspaceOwners.clear()
            return@LaunchedEffect
        }

        val liveTabIds = tabs.map { it.id }.toSet()
        workspaceOwners.keys
            .filter { it !in liveTabIds }
            .forEach { removedTabId ->
                workspaceOwners.remove(removedTabId)?.close()
            }

        tabs.forEach { tab ->
            val workspaceKey = tab.workspaceKey ?: return@forEach
            val workspace = Workspace(
                server = ServerRef.fromEndpoint(baseUrl),
                directory = (workspaceKey as? WorkspaceKey.Directory)?.value,
            )
            val currentOwner = workspaceOwners[tab.id]
            if (currentOwner == null ||
                currentOwner.workspace != workspace ||
                currentOwner.generation != generation
            ) {
                // Acquire the new owner BEFORE releasing the old one. Repositories are
                // shared and ref-counted per workspace key by SessionRepositoryProvider;
                // constructing the new owner first bumps the shared repository's ref-count
                // (transiently to 2 when the key is unchanged), so closing the old owner
                // afterwards drops it back to 1 instead of to 0. Closing first would evict
                // and close() a repository that other consumers (e.g. the session list, or
                // another tab on the same directory) are still using, surfacing as
                // "SessionRepository closed" and breaking submission until force-stop.
                val newOwner = WorkspaceRepositoryOwner(
                    tabId = tab.id,
                    workspace = workspace,
                    generation = generation,
                    sessionRepositoryProvider = sessionRepositoryProvider,
                )
                currentOwner?.close()
                workspaceOwners[tab.id] = newOwner
            }
        }
    }

    // Collect per-tab session presence outside page composition. HorizontalPager
    // composes only the active page, but background chat/sub-agent tabs still need
    // unread/busy updates when their repository state changes.
    tabs.forEach { tab ->
        val sessionId = tab.sessionId
        val workspaceOwner = workspaceOwners[tab.id]
        if (sessionId != null && workspaceOwner != null) {
            val sessionState by workspaceOwner.sessionRepository
                .sessionUiState(SessionId(sessionId))
                .collectAsState()
            LaunchedEffect(tab.id, activeTabId, sessionState.responseCompletedToken) {
                if (tab.id == activeTabId) {
                    tabReadTokens[tab.id] = sessionState.responseCompletedToken
                }
            }
            LaunchedEffect(tab.id, sessionState) {
                val readToken = tabReadTokens[tab.id] ?: sessionState.responseCompletedToken
                val hasUnread = sessionState.responseCompletedToken > readToken && sessionState.status !is SessionStatus.Busy
                tabConnectionStates[tab.id] = sessionState.presence(hasUnread = hasUnread)
            }
        } else {
            val tabSessionState by tab.connectionState.collectAsState()
            LaunchedEffect(tab.id, tabSessionState) {
                if (tabSessionState != null) {
                    tabConnectionStates[tab.id] = tabSessionState!!
                } else {
                    tabConnectionStates.remove(tab.id)
                    tabReadTokens.remove(tab.id)
                }
            }
        }
    }

    // Shared tab-close logic: PTY cleanup + state map cleanup + tabManager.closeTab.
    // Used by both TabBar close button and TabNavHost BackHandler.
    val closeTab: (String) -> Unit = remember(coroutineScope) {
        {
                tabId: String ->
            coroutineScope.launch {
                // Check if it's a terminal tab and delete the PTY
                val route = tabRoutes[tabId]
                if (route != null && route.startsWith("terminal/")) {
                    val ptyId = tabPtyIds[tabId]
                    if (ptyId != null) {
                        val api = connectionManager.getApi()
                        if (api != null) {
                            val result = safeApiCall { api.deletePtySession(ptyId) }
                            if (result is ApiResult.Error) {
                                AppLog.e(TAG, "Failed to delete PTY $ptyId: ${result.message}")
                            }
                        }
                    }
                }

                // Clean up tracked state for this tab
                tabRoutes.remove(tabId)
                tabPtyIds.remove(tabId)
                tabTitles.remove(tabId)
                tabIcons.remove(tabId)
                tabConnectionStates.remove(tabId)

                tabManager.closeTab(tabId)
            }
        }
    }

    // Snackbar for tab warning
    val snackbarHostState = remember { SnackbarHostState() }

    fun requestFilesTab(target: StartWorkTarget) {
        tabManager.focusOrCreateFilesTab(
            serverRef = target.serverRef,
            workspaceKey = target.workspaceKey,
        )
    }

    LaunchedEffect(showTabWarning) {
        if (showTabWarning) {
            snackbarHostState.showSnackbar(
                message = "Multiple tabs may affect performance",
                duration = SnackbarDuration.Short
            )
            tabManager.dismissTabWarning()
        }
    }

    LaunchedEffect(restoreError) {
        restoreError?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            restoreError = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = theme.background,
        contentWindowInsets = WindowInsets(0),
        modifier = modifier
    ) { innerPadding ->
        // We consume the status bar insets here so child Scaffolds don't double-pad.
        // The tab bar gets the status bar padding, then consumeWindowInsets tells
        // downstream composables that the status bar is already accounted for.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .consumeWindowInsets(WindowInsets.statusBars)
        ) {
            // Tab bar (no longer needs its own statusBarsPadding).
            // Top-level plus actions inherit the active tab target when possible.
            Box(modifier = Modifier.fillMaxWidth()) {
                TabBar(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    tabTitles = tabTitles,
                    tabIcons = tabIcons,
                    tabConnectionStates = tabConnectionStates,
                    onTabClick = { tabId ->
                        tabManager.focusTab(tabId)
                    },
                    onTabClose = closeTab,
                    onAddClick = {
                        showStartWorkSheet = true
                    },
                )
            }

            // Pager state for swipe between tabs
            val pagerState = rememberPagerState(
                initialPage = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0),
                pageCount = { tabs.size }
            )

            // Sync activeTabId -> pager (when tab clicked or closed)
            LaunchedEffect(activeTabId, tabs.size) {
                val index = tabs.indexOfFirst { it.id == activeTabId }
                if (index >= 0 && pagerState.currentPage != index) {
                    pagerState.animateScrollToPage(index)
                }
            }

            // Sync pager -> activeTabId (when user swipes)
            LaunchedEffect(pagerState.settledPage) {
                tabs.getOrNull(pagerState.settledPage)?.let { tab ->
                    if (tab.id != activeTabId) {
                        tabManager.focusTab(tab.id)
                    }
                }
            }

            // Tab content area with HorizontalPager for swipe between tabs
            val saveableStateHolder = rememberSaveableStateHolder()

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                key = { tabs.getOrNull(it)?.id ?: it.toString() },
                beyondViewportPageCount = 0
            ) { pageIndex ->
                tabs.getOrNull(pageIndex)?.let { tab ->
                    saveableStateHolder.SaveableStateProvider(tab.id) {
                        val navController = rememberNavController()

                        // Track route for title/icon
                        val backStackEntry by navController.currentBackStackEntryAsState()
                        LaunchedEffect(backStackEntry?.destination?.route, tab.sessionTitle) {
                            val route = backStackEntry?.destination?.route
                            // Only update when route is resolved — avoids overwriting
                            // seeded values with "Tab" during initial null-route composition
                            if (route != null) {
                                tabTitles[tab.id] = getTitleForRoute(
                                    route = route,
                                    labels = tabTitleLabels,
                                    sessionTitle = tab.sessionTitle,
                                    workspaceKey = tab.workspaceKey,
                                )
                                tabIcons[tab.id] = getIconForRoute(route)
                            }
                            // Track PTY ID if on a terminal route
                            val ptyId = backStackEntry?.arguments?.getString(Screen.Terminal.ARG_PTY_ID)
                            if (ptyId != null) {
                                tabPtyIds[tab.id] = ptyId
                                tabRoutes[tab.id] = Screen.Terminal.createRoute(ptyId)
                            }
                        }

                        val isActive = tab.id == activeTabId
                        val workspaceOwner = workspaceOwners[tab.id]
                        if (tab.isPinnedHome) {
                            homeScreen(
                                summary = HomeSummaryBuilder.build(
                                    savedServers = savedServers,
                                    connectionStates = homeConnectionStates,
                                    tabs = tabs,
                                ),
                                actions = HomeActions(
                                    onBrowseSessions = { target ->
                                        tabManager.createTab(
                                            startRoute = Screen.Sessions.route,
                                            workspaceKey = target.workspaceKey,
                                            serverRef = target.serverRef,
                                            focus = true,
                                        )
                                    },
                                    onOpenFiles = { target -> requestFilesTab(target) },
                                    onOpenTerminal = { target ->
                                        coroutineScope.launch {
                                            if (target.serverRef.endpointKey != currentServerRef?.endpointKey) {
                                                snackbarHostState.showSnackbar(
                                                    "Select and connect to ${target.serverRef.displayName} first",
                                                )
                                                return@launch
                                            }
                                            val api = connectionManager.getApi() ?: run {
                                                snackbarHostState.showSnackbar("Not connected to server")
                                                return@launch
                                            }
                                            val result = safeApiCall {
                                                api.createPtySession(
                                                    createPtyRequestForWorkspace(target.workspaceKey),
                                                )
                                            }
                                            if (result is ApiResult.Success) {
                                                tabManager.createTab(
                                                    startRoute = Screen.Terminal.createRoute(result.data.id),
                                                    workspaceKey = target.workspaceKey,
                                                    serverRef = target.serverRef,
                                                    focus = true,
                                                )
                                            } else if (result is ApiResult.Error) {
                                                snackbarHostState.showSnackbar(
                                                    "Failed to create terminal: ${result.message}",
                                                )
                                            }
                                        }
                                    },
                                    onChooseTarget = { showFilesTabPrompt = true },
                                    onWorkspaceDetailChanged = { homeDetailSelection = it },
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (workspaceOwner != null) {
                            TabNavHost(
                                navController = navController,
                                tabManager = tabManager,
                                tabId = tab.id,
                                serverRef = tab.serverRef ?: return@SaveableStateProvider,
                                onDisconnect = onDisconnect,
                                onCloseTab = { closeTab(tab.id) },
                                startRoute = tab.startRoute,
                                workspaceOwner = workspaceOwner,
                                onNewFilesTab = {
                                    val serverRef = tab.serverRef
                                    val workspaceKey = tab.workspaceKey
                                    if (serverRef != null && workspaceKey != null) {
                                        requestFilesTab(StartWorkTarget(serverRef, workspaceKey))
                                    } else {
                                        showFilesTabPrompt = true
                                    }
                                },
                                onNewTerminalTab = {
                                    coroutineScope.launch {
                                        val api = connectionManager.getApi() ?: run {
                                            AppLog.e(TAG, "Cannot create terminal: not connected")
                                            snackbarHostState.showSnackbar("Not connected to server")
                                            return@launch
                                        }
                                        val workspaceKey = tab.workspaceKey ?: run {
                                            AppLog.e(TAG, "Cannot create terminal: tab has no workspace identity")
                                            snackbarHostState.showSnackbar(
                                                "Cannot create terminal: workspace is unavailable"
                                            )
                                            return@launch
                                        }
                                        val result = safeApiCall {
                                            api.createPtySession(createPtyRequestForWorkspace(workspaceKey))
                                        }
                                        when (result) {
                                            is ApiResult.Success -> {
                                                val ptyId = result.data.id
                                                tabManager.createTab(
                                                    startRoute = Screen.Terminal.createRoute(ptyId),
                                                    workspaceKey = workspaceKey,
                                                    serverRef = tab.serverRef ?: return@launch,
                                                    focus = true,
                                                )
                                            }
                                            is ApiResult.Error -> {
                                                AppLog.e(TAG, "Failed to create PTY: ${result.message}")
                                                snackbarHostState.showSnackbar(
                                                    "Failed to create terminal: ${result.message}"
                                                )
                                            }
                                        }
                                    }
                                },
                                isActiveTab = isActive,
                                onConnectionStateChanged = { state ->
                                    tab.updateConnectionState(state)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (baseUrl == null || generation == null) {
                                    Text(
                                        text = "Not connected to server",
                                        color = theme.textMuted,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStartWorkSheet) {
        val availableServers = savedServers.map {
            ServerRef.fromEndpointKey(it.endpointKey, it.displayName)
        }
        val rawContext = if (tabManager.activeTab?.isPinnedHome == true) {
            StartWorkContext(
                source = StartWorkSource.HomeWorkspaceDetail,
                selection = homeDetailSelection,
            )
        } else {
            startWorkContextFor(tabManager.activeTab)
        }
        val startContext = rawContext.copy(
            selection = rawContext.selection.validatedAgainst(availableServers),
        )
        val target = startContext.selectedTarget
        TuiAlertDialog(
            onDismissRequest = { showStartWorkSheet = false },
            title = "Start work",
            confirmButton = {
                TuiTextButton(onClick = { showStartWorkSheet = false }) {
                    Text("Cancel")
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = target?.let {
                        val workspace = workspaceLabel(it.workspaceKey, tabTitleLabels)
                            ?: workspaceSubtitle(it.workspaceKey)
                        "Target: ${it.serverRef.displayName} · $workspace"
                    } ?: "Choose a target before creating work.",
                    color = theme.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                StartWorkActionRow(
                    label = "New chat",
                    description = "Start a chat in the target workspace.",
                    marker = "C",
                    onClick = {
                        showStartWorkSheet = false
                        if (target == null) {
                            showFilesTabPrompt = true
                        } else {
                            tabManager.createTab(
                                startRoute = Screen.Sessions.route,
                                workspaceKey = target.workspaceKey,
                                serverRef = target.serverRef,
                                focus = true,
                            )
                        }
                    },
                )
                StartWorkActionRow(
                    label = "Browse sessions",
                    description = "Browse sessions for the exact target workspace.",
                    marker = "S",
                    onClick = {
                        showStartWorkSheet = false
                        if (target == null) {
                            showFilesTabPrompt = true
                        } else {
                            tabManager.createTab(
                                startRoute = Screen.Sessions.route,
                                workspaceKey = target.workspaceKey,
                                serverRef = target.serverRef,
                                focus = true,
                            )
                        }
                    },
                )
                StartWorkActionRow(
                    label = "Files tab",
                    description = "Open files for the target workspace.",
                    marker = "F",
                    onClick = {
                        showStartWorkSheet = false
                        if (target == null) showFilesTabPrompt = true else requestFilesTab(target)
                    },
                )
                StartWorkActionRow(
                    label = "Terminal",
                    description = "Create a terminal for the target workspace.",
                    marker = "T",
                    onClick = {
                        showStartWorkSheet = false
                        if (target == null) {
                            showFilesTabPrompt = true
                        } else {
                            coroutineScope.launch {
                                if (target.serverRef.endpointKey != currentServerRef?.endpointKey) {
                                    snackbarHostState.showSnackbar(
                                        "Select and connect to ${target.serverRef.displayName} first",
                                    )
                                    return@launch
                                }
                                val api = connectionManager.getApi() ?: run {
                                    snackbarHostState.showSnackbar("Not connected to server")
                                    return@launch
                                }
                                val result = safeApiCall {
                                    api.createPtySession(createPtyRequestForWorkspace(target.workspaceKey))
                                }
                                if (result is ApiResult.Success) {
                                    tabManager.createTab(
                                        startRoute = Screen.Terminal.createRoute(result.data.id),
                                        workspaceKey = target.workspaceKey,
                                        serverRef = target.serverRef,
                                        focus = true,
                                    )
                                } else if (result is ApiResult.Error) {
                                    snackbarHostState.showSnackbar("Failed to create terminal: ${result.message}")
                                }
                            }
                        }
                    },
                )
                StartWorkActionRow(
                    label = "Choose another target",
                    description = "Pick a different workspace target before creating work.",
                    marker = "…",
                    onClick = {
                        showStartWorkSheet = false
                        showFilesTabPrompt = true
                    },
                )
            }
        }
    }

    if (showFilesTabPrompt) {
        val availableEndpointKeys = savedServers.mapTo(mutableSetOf()) { it.endpointKey }
        val openTargets = tabs.mapNotNull { tab ->
            val serverRef = tab.serverRef ?: return@mapNotNull null
            val workspaceKey = tab.workspaceKey ?: return@mapNotNull null
            StartWorkTarget(serverRef, workspaceKey)
        }.filter { it.serverRef.endpointKey in availableEndpointKeys }
            .distinct()
        val noProjectTargets = savedServers.map {
            StartWorkTarget(
                ServerRef.fromEndpointKey(it.endpointKey, it.displayName),
                WorkspaceKey.Global,
            )
        }
        fun selectTarget(target: StartWorkTarget) {
            requestFilesTab(target)
            showFilesTabPrompt = false
        }

        TuiAlertDialog(
            onDismissRequest = { showFilesTabPrompt = false },
            title = "Select workspace",
            confirmButton = {
                TuiTextButton(onClick = { showFilesTabPrompt = false }) {
                    Text("Cancel")
                }
            }
        ) {
            Text("Choose an exact server and workspace:")
            noProjectTargets.forEach { target ->
                FilesWorkspaceOption(
                    title = "${target.serverRef.displayName} · No project context",
                    subtitle = "Explicit server scope",
                    marker = "◆",
                    onClick = { selectTarget(target) },
                )
            }
            openTargets.filter { it.workspaceKey != WorkspaceKey.Global }.forEach { target ->
                FilesWorkspaceOption(
                    title = workspaceLabel(target.workspaceKey, tabTitleLabels) ?: "Missing workspace",
                    subtitle = "${target.serverRef.displayName} · ${workspaceSubtitle(target.workspaceKey)}",
                    marker = "◇",
                    onClick = { selectTarget(target) },
                )
            }
        }
    }
}
internal fun createPtyRequestForWorkspace(workspaceKey: WorkspaceKey): CreatePtyRequest = CreatePtyRequest(
    cwd = (workspaceKey as? WorkspaceKey.Directory)?.value,
    title = terminalTitle(workspaceKey),
)

private fun terminalTitle(workspaceKey: WorkspaceKey): String? = when (workspaceKey) {
    is WorkspaceKey.Directory -> workspaceKey.value.trimEnd('/').substringAfterLast('/').ifBlank { "Terminal" }
    WorkspaceKey.Global -> null
    is WorkspaceKey.SessionScoped -> workspaceKey.sessionId.value
}

private fun workspaceSubtitle(workspaceKey: WorkspaceKey): String = when (workspaceKey) {
    is WorkspaceKey.Directory -> workspaceKey.value
    WorkspaceKey.Global -> "No project context"
    is WorkspaceKey.SessionScoped -> "Session-scoped workspace"
}

@Composable
private fun StartWorkActionRow(
    label: String,
    description: String,
    marker: String,
    onClick: () -> Unit,
) {
    FilesWorkspaceOption(
        title = label,
        subtitle = description,
        marker = marker,
        onClick = onClick,
    )
}

@Composable
private fun FilesWorkspaceOption(
    title: String,
    subtitle: String,
    marker: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        color = theme.backgroundElement,
        shape = TuiShapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ">",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.accent.copy(alpha = 0.3f),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = marker,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.textMuted,
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.accent,
            )
        }
    }
}
