package org.coresense.itantra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.components.OfflineBanner
import org.coresense.itantra.ui.components.PermissionRationaleDialog
import org.coresense.itantra.ui.screens.*
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.ITantraTheme
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

enum class AppState {
    SPLASH,
    LANGUAGE,
    LOGIN,
    USER_INFO,
    HOME
}

enum class NavigationTab(val title: String, val icon: ImageVector) {
    AUDIO("Audio", Icons.Default.Mic),
    EMERGENCY_SOS("SOS", Icons.Default.Emergency),
    TRACK_ME("Track Me", Icons.Default.LocationOn),
    CHATS("Chats", Icons.Default.Chat)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_SCAN)
                list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            return list.toTypedArray()
        }

    private var hasAllPermissions by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        hasAllPermissions = missing.isEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            ITantraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var appState by remember { mutableStateOf(AppState.SPLASH) }

                    when (appState) {
                        AppState.SPLASH -> {
                            SplashScreen(
                                onTimeout = { appState = AppState.LANGUAGE }
                            )
                        }
                        AppState.LANGUAGE -> {
                            LanguageScreen(
                                viewModel = viewModel,
                                onNext = { appState = AppState.LOGIN }
                            )
                        }
                        AppState.LOGIN -> {
                            LoginScreen(
                                viewModel = viewModel,
                                hasAllPermissions = hasAllPermissions,
                                onRequestPermissions = { checkAndRequestPermissions() },
                                onLoginSuccess = { appState = AppState.USER_INFO }
                            )
                        }
                        AppState.USER_INFO -> {
                            UserInfoScreen(viewModel = viewModel, 
                                onContinue = { appState = AppState.HOME }
                            )
                        }
                        AppState.HOME -> {
                            MainAppScaffold(
                                viewModel = viewModel,
                                onSignOut = { appState = AppState.LOGIN }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        hasAllPermissions = missing.isEmpty()
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        hasAllPermissions = missing.isEmpty()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    viewModel: MainViewModel,
    onSignOut: () -> Unit = {}
) {
    var currentTab by remember { mutableStateOf(NavigationTab.AUDIO) }
    var inDemoScreen by remember { mutableStateOf(false) }
    var inReceiverScreen by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showMyProfile by remember { mutableStateOf(false) }
    var showSosHistory by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showTopQuestions by remember { mutableStateOf(false) }

    val sosHistoryMessages by viewModel.sosHistory.collectAsState()
    val selectedLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(selectedLang) { org.coresense.itantra.ui.i18n.AppLocalization.getStrings(selectedLang) }

    if (inReceiverScreen) {
        ReceiverScreen(
            viewModel = viewModel,
            onNavigateBack = { inReceiverScreen = false }
        )
    } else if (inDemoScreen) {
        DemoScreen(
            viewModel = viewModel,
            onNavigateBack = { inDemoScreen = false }
        )
    } else {
        if (showMyProfile) {
            AlertDialog(
                onDismissRequest = { showMyProfile = false },
                title = { Text("My Profile") },
                text = { Text("User ID: USER01\nAadhar: XXXX-XXXX-XXXX\nAddress: Unknown\nEmail: user@example.com\nBirth Date: 01/01/1990\nGender: M\nMobile: 9999999999") },
                confirmButton = {
                    TextButton(onClick = { showMyProfile = false }) { Text("Close", color = PrimaryNeonGreen) }
                },
                containerColor = DarkSurface,
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        if (showSosHistory) {
            AlertDialog(
                onDismissRequest = { showSosHistory = false },
                title = { Text("SOS History") },
                text = {
                    Column {
                        if (sosHistoryMessages.isEmpty()) {
                            Text("No recent SOS alerts.")
                        } else {
                            sosHistoryMessages.forEach { msg ->
                                Text("• ${msg.text}", color = Color.Red)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSosHistory = false }) { Text("Close", color = PrimaryNeonGreen) }
                },
                containerColor = DarkSurface,
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        if (showNotifications) {
            AlertDialog(
                onDismissRequest = { showNotifications = false },
                title = { Text("Notifications") },
                text = { Text("All systems nominal. No new notifications.") },
                confirmButton = {
                    TextButton(onClick = { showNotifications = false }) { Text("Close", color = PrimaryNeonGreen) }
                },
                containerColor = DarkSurface,
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        if (showTopQuestions) {
            AlertDialog(
                onDismissRequest = { showTopQuestions = false },
                title = { Text("Top Questions") },
                text = {
                    Column {
                        Text("Q: How does offline mesh work?")
                        Text("A: It uses Wi-Fi Direct and Bluetooth SPP.", color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Q: Can I send an SOS without internet?")
                        Text("A: Yes, SOS packets are sent directly to nearby peers.", color = Color.Gray)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTopQuestions = false }) { Text("Close", color = PrimaryNeonGreen) }
                },
                containerColor = DarkSurface,
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("iTantra") },
                        actions = {
                            Box {
                                IconButton(onClick = { showProfileMenu = true }) {
                                    Icon(imageVector = Icons.Default.AccountCircle, contentDescription = "Profile")
                                }
                                DropdownMenu(
                                    expanded = showProfileMenu,
                                    onDismissRequest = { showProfileMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("My Profile") },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showMyProfile = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("SOS History") },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showSosHistory = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Notifications") },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showNotifications = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Top Questions") },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showTopQuestions = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Department Inbox") },
                                        onClick = { 
                                            showProfileMenu = false 
                                            inReceiverScreen = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sign Out") },
                                        onClick = {
                                            showProfileMenu = false
                                            onSignOut()
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = DarkSurface,
                            titleContentColor = PrimaryNeonGreen,
                            actionIconContentColor = PrimaryNeonGreen
                        )
                    )
                    OfflineBanner(text = strings.offlineBanner)
                }
            },
            bottomBar = {
                NavigationBar(
                    containerColor = DarkSurface
                ) {
                    NavigationTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = (currentTab == tab),
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title
                                )
                            },
                            label = { Text(tab.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PrimaryNeonGreen,
                                selectedTextColor = PrimaryNeonGreen,
                                indicatorColor = DarkSurface
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (currentTab) {
                    NavigationTab.AUDIO -> RadioScreen(viewModel = viewModel)
                    NavigationTab.EMERGENCY_SOS -> SosScreen(viewModel = viewModel)
                    NavigationTab.TRACK_ME -> TrackMeScreen(viewModel = viewModel)
                    NavigationTab.CHATS -> ChatsScreen(viewModel = viewModel)
                }
            }
        }
    }
}

