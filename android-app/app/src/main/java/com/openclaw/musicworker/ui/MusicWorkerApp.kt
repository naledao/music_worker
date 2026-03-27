package com.openclaw.musicworker.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openclaw.musicworker.ui.screens.HomeScreen
import com.openclaw.musicworker.ui.screens.ResultsScreen
import com.openclaw.musicworker.ui.screens.SearchScreen
import com.openclaw.musicworker.ui.screens.SettingsScreen

private data class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    AppDestination(route = "home", label = "首页", icon = Icons.Rounded.Home),
    AppDestination(route = "search", label = "搜索", icon = Icons.Rounded.Search),
    AppDestination(route = "results", label = "结果", icon = Icons.AutoMirrored.Rounded.List),
    AppDestination(route = "settings", label = "设置", icon = Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicWorkerApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "home"
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        viewModel.saveDownloadDirectory(uri.toString())
    }
    LaunchedEffect(uiState.settings.pendingInstallAppUpdateUri) {
        val pendingUri = uiState.settings.pendingInstallAppUpdateUri ?: return@LaunchedEffect
        val contentUri = runCatching { Uri.parse(pendingUri) }.getOrNull()
        if (contentUri == null) {
            viewModel.onAppUpdateInstallHandled(errorMessage = "更新包地址无效")
            return@LaunchedEffect
        }

        val canInstallPackages = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        val intent = if (canInstallPackages) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching { context.startActivity(intent) }
            .onSuccess {
                if (canInstallPackages) {
                    viewModel.onAppUpdateInstallHandled(message = "已拉起系统安装器")
                } else {
                    viewModel.onAppUpdateInstallHandled(message = "请先允许安装未知应用，然后再点“安装已下载更新”")
                }
            }
            .onFailure { error ->
                viewModel.onAppUpdateInstallHandled(errorMessage = error.message ?: "拉起安装器失败")
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = destinations.firstOrNull { it.route == currentRoute }?.label ?: "Music Worker")
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(imageVector = destination.icon, contentDescription = destination.label) },
                        label = { Text(text = destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    state = uiState.home,
                    downloadState = uiState.download,
                    serverConfig = uiState.serverConfig,
                    onRefresh = viewModel::refreshDashboard,
                )
            }
            composable("search") {
                SearchScreen(
                    state = uiState.search,
                    onKeywordChange = viewModel::updateSearchInput,
                    onSearch = {
                        viewModel.search()
                        navController.navigate("results") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("results") {
                ResultsScreen(
                    state = uiState.search,
                    downloadState = uiState.download,
                    onRetrySearch = viewModel::search,
                    onDownload = viewModel::startDownload,
                    onRetryExport = viewModel::retryExportCurrentTask,
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = uiState.settings,
                    serverConfig = uiState.serverConfig,
                    onHostChange = viewModel::updateHost,
                    onPortChange = viewModel::updatePort,
                    onSaveConfig = viewModel::saveServerConfig,
                    onRefresh = viewModel::refreshSettingsPanel,
                    onProxySelect = viewModel::selectProxy,
                    onPickDownloadDirectory = {
                        val initialUri = uiState.settings.downloadDirectoryUri?.let(Uri::parse)
                        directoryPicker.launch(initialUri)
                    },
                    onClearDownloadDirectory = viewModel::clearDownloadDirectory,
                    onClearPrivateStorage = viewModel::clearPrivateStorage,
                    onCheckAppUpdate = viewModel::checkAppUpdate,
                    onDownloadAndInstallAppUpdate = viewModel::downloadAndInstallAppUpdate,
                )
            }
        }
    }
}
