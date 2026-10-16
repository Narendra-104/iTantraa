package org.coresense.itantra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    SIGN_UP,
    HOME
}

enum class NavigationTab(val title: String, val icon: ImageVector) {
    AUDIO("Audio", Icons.Default.Mic),
    DIAL("Dial", Icons.Default.Call),
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
        handleIncomingIntent(intent)
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
                                onLoginSuccess = { appState = AppState.HOME },
                                onNavigateToSignUp = { appState = AppState.SIGN_UP }
                            )
                        }
                        AppState.SIGN_UP -> {
                            SignUpScreen(
                                viewModel = viewModel,
                                onCreateAccountSuccess = { appState = AppState.HOME },
                                onNavigateToLogin = { appState = AppState.LOGIN }
                            )
                        }
                        AppState.HOME -> {
                            MainAppScaffold(
                                viewModel = viewModel,
                                onSignOut = {
                                    viewModel.identityRepository.clearIdentity()
                                    viewModel.callsign.value = ""
                                    appState = AppState.LOGIN
                                }
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("EXTRA_SILENCE_SOS", false)) {
            viewModel.cancelActiveSos()
        }
        when (intent.action) {
            "ACTION_ACCEPT_CALL" -> {
                viewModel.acceptIncomingCall()
                viewModel.targetNavigationTab.value = "DIAL"
            }
            "ACTION_DECLINE_CALL" -> {
                viewModel.rejectIncomingCall()
            }
        }
        val navTab = intent.getStringExtra("EXTRA_NAV_TAB")
        if (navTab != null) {
            viewModel.targetNavigationTab.value = navTab
        }
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
    val navTarget by viewModel.targetNavigationTab.collectAsState()

    var inDemoScreen by remember { mutableStateOf(false) }
    var inReceiverScreen by remember { mutableStateOf(false) }
    var inSettingsScreen by remember { mutableStateOf(false) }
    var inManageLanguagesScreen by remember { mutableStateOf(false) }
    var inTtsScreen by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showMyProfile by remember { mutableStateOf(false) }
    var showSosHistory by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showTopQuestions by remember { mutableStateOf(false) }

    LaunchedEffect(navTarget) {
        navTarget?.let { target ->
            when (target) {
                "CHATS" -> currentTab = NavigationTab.CHATS
                "SOS" -> currentTab = NavigationTab.EMERGENCY_SOS
                "DIAL" -> currentTab = NavigationTab.DIAL
                "AUDIO" -> currentTab = NavigationTab.AUDIO
                "TRACK_ME" -> currentTab = NavigationTab.TRACK_ME
            }
            inSettingsScreen = false
            inDemoScreen = false
            inTtsScreen = false
            inReceiverScreen = false
            viewModel.targetNavigationTab.value = null
        }
    }

    val sosHistoryMessages by viewModel.sosHistory.collectAsState()
    val selectedLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(selectedLang) { org.coresense.itantra.ui.i18n.AppLocalization.getStrings(selectedLang) }

    if (inManageLanguagesScreen) {
        ManageLanguagesScreen(
            viewModel = viewModel,
            onNavigateBack = { inManageLanguagesScreen = false }
        )
    } else if (inSettingsScreen) {
        SettingsScreen(
            viewModel = viewModel,
            onNavigateToDemo = { inDemoScreen = true },
            onNavigateToManageLanguages = { inManageLanguagesScreen = true },
            onNavigateToTts = { inTtsScreen = true },
            onNavigateBack = { inSettingsScreen = false }
        )
    } else if (inTtsScreen) {
        TtsScreen(
            viewModel = viewModel,
            onNavigateBack = { inTtsScreen = false }
        )
    } else if (inReceiverScreen) {
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
            MyProfileDialog(
                viewModel = viewModel,
                onDismiss = { showMyProfile = false }
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
                title = { Text("Top Questions & Field FAQ", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Q: How does offline mesh communication work?", fontWeight = FontWeight.SemiBold, color = PrimaryNeonGreen, fontSize = 13.sp)
                        Text("A: It uses direct phone-to-phone Wi-Fi Direct and local hotspot sockets with zero internet dependency.", color = Color.LightGray, fontSize = 12.sp)
                        HorizontalDivider(color = Color.DarkGray)
                        Text("Q: Can I send an SOS without cellular coverage?", fontWeight = FontWeight.SemiBold, color = PrimaryNeonGreen, fontSize = 13.sp)
                        Text("A: Yes. SOS emergency packets are broadcast directly to all reachable mesh peers with live GPS coordinates.", color = Color.LightGray, fontSize = 12.sp)
                        HorizontalDivider(color = Color.DarkGray)
                        Text("Q: How do department messages route?", fontWeight = FontWeight.SemiBold, color = PrimaryNeonGreen, fontSize = 13.sp)
                        Text("A: Addressed packets only route to matching department logins (NDRF, Medical, Police, Fire, Railway, Civil Defence).", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                showTopQuestions = false
                                currentTab = NavigationTab.CHATS
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Chat Quick Questions", color = DarkSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTopQuestions = false }) { Text("Close", color = Color.Gray) }
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
                        title = { Text(strings.appTitle) },
                        actions = {
                            IconButton(onClick = { inTtsScreen = true }) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = strings.ttsMenu,
                                    tint = PrimaryNeonGreen
                                )
                            }
                            Box {
                                IconButton(onClick = { showProfileMenu = true }) {
                                    Icon(imageVector = Icons.Default.AccountCircle, contentDescription = "Profile")
                                }
                                DropdownMenu(
                                    expanded = showProfileMenu,
                                    onDismissRequest = { showProfileMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(strings.myProfileMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showMyProfile = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.sosHistoryMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showSosHistory = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.notificationsMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showNotifications = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.topQuestionsMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            showTopQuestions = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.ttsMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            inTtsScreen = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.departmentInboxMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            inReceiverScreen = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.linkSettingsMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            inSettingsScreen = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.manageLanguagesMenu) },
                                        onClick = { 
                                            showProfileMenu = false 
                                            inManageLanguagesScreen = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.signOutMenu) },
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
                }
            },
            bottomBar = {
                NavigationBar(
                    containerColor = DarkSurface
                ) {
                    NavigationTab.entries.forEach { tab ->
                        val tabTitle = when (tab) {
                            NavigationTab.AUDIO -> strings.tabRadio
                            NavigationTab.DIAL -> strings.tabDial
                            NavigationTab.EMERGENCY_SOS -> strings.tabSos
                            NavigationTab.TRACK_ME -> strings.tabTrackMe
                            NavigationTab.CHATS -> strings.tabChats
                        }
                        NavigationBarItem(
                            selected = (currentTab == tab),
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tabTitle
                                )
                            },
                            label = { Text(tabTitle) },
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
                    NavigationTab.AUDIO -> RadioScreen(
                        viewModel = viewModel,
                        onOpenSettings = { inSettingsScreen = true }
                    )
                    NavigationTab.DIAL -> DialScreen(viewModel = viewModel)
                    NavigationTab.EMERGENCY_SOS -> SosScreen(viewModel = viewModel)
                    NavigationTab.TRACK_ME -> TrackMeScreen(viewModel = viewModel)
                    NavigationTab.CHATS -> ChatsScreen(viewModel = viewModel)
                }

                val callSessionState by viewModel.callSessionState.collectAsState()
                val incomingCallFrom by viewModel.incomingCallFrom.collectAsState()

                if (callSessionState == org.coresense.itantra.ui.CallSessionState.INCOMING_CALL && currentTab != NavigationTab.DIAL) {
                    AlertDialog(
                        onDismissRequest = { /* stay on screen */ },
                        title = {
                            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PhoneInTalk,
                                    contentDescription = null,
                                    tint = PrimaryNeonGreen
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                                Text("Incoming Call", color = PrimaryNeonGreen, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        },
                        text = {
                            Text(
                                "Incoming voice call from ${incomingCallFrom ?: "Connected Peer"}.\nAccept to start hands-free speech-over-packet session.",
                                color = Color.White
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.acceptIncomingCall()
                                    currentTab = NavigationTab.DIAL
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryNeonGreen,
                                    contentColor = DarkSurface
                                )
                            ) {
                                Text("ACCEPT", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            Button(
                                onClick = { viewModel.rejectIncomingCall() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                            ) {
                                Text("DECLINE")
                            }
                        },
                        containerColor = org.coresense.itantra.ui.theme.DarkSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileViewRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            text = value.ifBlank { "—" },
            color = if (highlight) PrimaryNeonGreen else Color.White,
            fontSize = 13.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun MyProfileDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val userProfile by viewModel.userProfile.collectAsState()
    val identity by viewModel.identityRepository.userIdentity.collectAsState()

    var isEditing by remember { mutableStateOf(false) }

    var editName by remember(userProfile, identity) {
        mutableStateOf(userProfile?.name ?: identity?.name ?: "")
    }
    var editAadhar by remember(userProfile) {
        mutableStateOf(userProfile?.aadhar ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_AADHAR))
    }
    var editEmail by remember(userProfile) {
        mutableStateOf(userProfile?.email ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_EMAIL))
    }
    var editMobile by remember(userProfile) {
        mutableStateOf(userProfile?.mobileNumber ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_MOBILE))
    }
    var editAddress by remember(userProfile) {
        mutableStateOf(userProfile?.address ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_ADDRESS))
    }
    var editBirthDate by remember(userProfile) {
        mutableStateOf(userProfile?.birthDate ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_BIRTH_DATE))
    }
    var editBloodGroup by remember(userProfile) {
        mutableStateOf(userProfile?.bloodGroup ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_BLOOD_GROUP, "Unknown"))
    }
    var editGender by remember(userProfile) {
        mutableStateOf(userProfile?.gender ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_GENDER))
    }
    val bloodOptions = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-", "Unknown")
    var bloodMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (isEditing) "Edit Profile" else "My Profile", fontWeight = FontWeight.Bold)
                if (!isEditing) {
                    IconButton(onClick = { isEditing = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Profile",
                            tint = PrimaryNeonGreen
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isEditing) {
                    val currentName = userProfile?.name?.ifBlank { null } ?: identity?.name ?: "Citizen"
                    val currentId = userProfile?.userId?.ifBlank { null } ?: identity?.userId ?: "USER01"
                    val deviceId = viewModel.identityRepository.getDeviceId()
                    val aadhar = userProfile?.aadhar?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_AADHAR, "Not Specified")
                    val email = userProfile?.email?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_EMAIL, "Not Specified")
                    val mobile = userProfile?.mobileNumber?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_MOBILE, "Not Specified")
                    val address = userProfile?.address?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_ADDRESS, "Not Specified")
                    val birthDate = userProfile?.birthDate?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_BIRTH_DATE, "Not Specified")
                    val bloodGroup = userProfile?.bloodGroup?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_BLOOD_GROUP, "Unknown")
                    val gender = userProfile?.gender?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_GENDER, "Not Specified")

                    ProfileViewRow("Name", currentName)
                    ProfileViewRow("User ID", currentId)
                    ProfileViewRow("Device ID", deviceId)
                    ProfileViewRow("Aadhar", aadhar)
                    ProfileViewRow("Email", email)
                    ProfileViewRow("Mobile Number", mobile)
                    ProfileViewRow("Address", address)
                    HorizontalDivider(color = Color.DarkGray)
                    ProfileViewRow("Birth Date", birthDate)
                    ProfileViewRow("Blood Group", bloodGroup, highlight = true)
                    ProfileViewRow("Gender", gender)
                } else {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Full Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAadhar,
                        onValueChange = { editAadhar = it },
                        label = { Text("Aadhar") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editEmail,
                        onValueChange = { editEmail = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editMobile,
                        onValueChange = { editMobile = it },
                        label = { Text("Mobile Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAddress,
                        onValueChange = { editAddress = it },
                        label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editBirthDate,
                        onValueChange = { editBirthDate = it },
                        label = { Text("Birth Date (DD/MM/YYYY)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = editBloodGroup,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Blood Group") },
                            trailingIcon = {
                                IconButton(onClick = { bloodMenuExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { bloodMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = bloodMenuExpanded,
                            onDismissRequest = { bloodMenuExpanded = false }
                        ) {
                            bloodOptions.forEach { bg ->
                                DropdownMenuItem(
                                    text = { Text(bg) },
                                    onClick = {
                                        editBloodGroup = bg
                                        bloodMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = editGender,
                        onValueChange = { editGender = it },
                        label = { Text("Gender") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (isEditing) {
                Button(
                    onClick = {
                        val currentUserId = userProfile?.userId ?: identity?.userId ?: viewModel.identityRepository.getDeviceId()
                        val updated = org.coresense.itantra.storage.UserProfileEntity(
                            userId = currentUserId,
                            name = editName.ifBlank { "User" },
                            aadhar = editAadhar,
                            email = editEmail,
                            mobileNumber = editMobile,
                            address = editAddress,
                            birthDate = editBirthDate,
                            bloodGroup = editBloodGroup.ifBlank { "Unknown" },
                            gender = editGender
                        )
                        viewModel.saveUserProfile(updated)
                        isEditing = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen)
                ) {
                    Text("Save", color = DarkSurface, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = PrimaryNeonGreen)
                }
            }
        },
        dismissButton = {
            if (isEditing) {
                TextButton(onClick = {
                    editName = userProfile?.name ?: identity?.name ?: ""
                    editAadhar = userProfile?.aadhar ?: ""
                    editEmail = userProfile?.email ?: ""
                    editMobile = userProfile?.mobileNumber ?: ""
                    editAddress = userProfile?.address ?: ""
                    editBirthDate = userProfile?.birthDate ?: ""
                    editBloodGroup = userProfile?.bloodGroup ?: "Unknown"
                    editGender = userProfile?.gender ?: ""
                    isEditing = false
                }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        },
        containerColor = DarkSurface,
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}


