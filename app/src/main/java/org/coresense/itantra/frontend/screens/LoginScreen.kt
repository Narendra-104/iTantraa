package org.coresense.itantra.frontend.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.data.repository.IdentityRepository
import org.coresense.itantra.core.identity.Role
import org.coresense.itantra.frontend.viewmodel.MainViewModel
import org.coresense.itantra.frontend.i18n.AppLocalization

@Composable
fun LoginScreen(
    viewModel: MainViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateToSignUp: () -> Unit,
    hasAllPermissions: Boolean = true,
    onRequestPermissions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val savedProfile by viewModel.userProfile.collectAsState()
    val identityState by viewModel.identityRepository.userIdentity.collectAsState()
    val currentLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(currentLang) { AppLocalization.getStrings(currentLang) }

    var loginId by remember(savedProfile, identityState) {
        val initial = savedProfile?.mobileNumber?.takeIf { it.isNotBlank() }
            ?: savedProfile?.name?.takeIf { it.isNotBlank() }
            ?: identityState?.name?.takeIf { it.isNotBlank() && it != "User" }
            ?: ""
        mutableStateOf(initial)
    }

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val brandNavy = Color(0xFF0F1E36)
    val brandOrange = Color(0xFFC07D3E)
    val textMuted = Color(0xFF64748B)
    val borderColor = Color(0xFFE2E8F0)

    if (showForgotPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showForgotPasswordDialog = false },
            title = {
                Text(
                    text = "Offline Account Recovery",
                    fontWeight = FontWeight.Bold,
                    color = brandNavy
                )
            },
            text = {
                Text(
                    text = "iTantra operates in offline, decentralized mesh networks without central cloud servers. If you forgot your password, tap 'Create Account' to re-register your profile on this device.",
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )
            },
            confirmButton = {
                TextButton(onClick = { showForgotPasswordDialog = false }) {
                    Text("OK", color = brandOrange, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // App Brand Header - Symbol removed as requested
            Text(
                text = "iTantra",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = brandNavy
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = "Stay Connected. ",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF334155)
                )
                Text(
                    text = "Stay Safe.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = brandOrange
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Welcome Back Title
            Text(
                text = strings.welcomeBack,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = brandNavy
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = strings.signInToAccess,
                fontSize = 13.sp,
                color = textMuted
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Login Card Container
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Mobile Number or User ID
                    OutlinedTextField(
                        value = loginId,
                        onValueChange = {
                            loginId = it
                            errorMessage = null
                        },
                        label = { Text(strings.mobileOrUserId, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = textMuted
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = brandNavy,
                            unfocusedBorderColor = borderColor,
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )

                    // Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = { Text(strings.passwordLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = textMuted
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = textMuted
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = brandNavy,
                            unfocusedBorderColor = borderColor,
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )

                    // Forgot Password
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = strings.forgotPassword,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = brandOrange,
                            modifier = Modifier.clickable { showForgotPasswordDialog = true }
                        )
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFDC2626),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Login Button with strict account validation
                    Button(
                        onClick = {
                            val isAccountCreated = viewModel.identityRepository.isAccountCreated() || savedProfile != null
                            if (!isAccountCreated) {
                                errorMessage = strings.noAccountError
                                return@Button
                            }

                            if (loginId.isBlank() || password.isBlank()) {
                                errorMessage = strings.emptyCredentialsError
                                return@Button
                            }

                            val savedPassword = viewModel.identityRepository.getSavedPassword()
                            val validIdentifiers = listOfNotNull(
                                savedProfile?.mobileNumber?.takeIf { it.isNotBlank() },
                                savedProfile?.userId?.takeIf { it.isNotBlank() },
                                savedProfile?.email?.takeIf { it.isNotBlank() },
                                savedProfile?.name?.takeIf { it.isNotBlank() },
                                viewModel.identityRepository.getProfileDetail(IdentityRepository.KEY_PROFILE_MOBILE).takeIf { it.isNotBlank() },
                                viewModel.identityRepository.getProfileDetail(IdentityRepository.KEY_PROFILE_EMAIL).takeIf { it.isNotBlank() },
                                viewModel.identityRepository.getProfileDetail(IdentityRepository.KEY_USER_ID).takeIf { it.isNotBlank() },
                                viewModel.identityRepository.getProfileDetail(IdentityRepository.KEY_NAME).takeIf { it.isNotBlank() },
                                viewModel.identityRepository.getDeviceId()
                            )

                            val passwordMatches = savedPassword.isEmpty() || password == savedPassword
                            val idMatches = validIdentifiers.isEmpty() || validIdentifiers.any { it.equals(loginId.trim(), ignoreCase = true) }

                            if (!passwordMatches || !idMatches) {
                                errorMessage = strings.invalidCredentialsError
                                return@Button
                            }

                            val resolvedName = savedProfile?.name?.takeIf { it.isNotBlank() }
                                ?: viewModel.identityRepository.getProfileDetail(IdentityRepository.KEY_NAME).takeIf { it.isNotBlank() }
                                ?: loginId.trim()

                            viewModel.callsign.value = resolvedName
                            val currentRole = identityState?.role ?: Role.USER
                            val currentDept = identityState?.departmentId
                            viewModel.identityRepository.saveIdentity(
                                name = resolvedName,
                                role = currentRole,
                                departmentId = currentDept
                            )
                            onLoginSuccess()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = brandNavy)
                    ) {
                        Text(
                            text = strings.loginBtn,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Divider with "New to iTantra?"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFE2E8F0)
                        )
                        Text(
                            text = "  ${strings.newToApp}  ",
                            fontSize = 12.sp,
                            color = textMuted
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFE2E8F0)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Create Account Outlined Button
                    OutlinedButton(
                        onClick = onNavigateToSignUp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, brandOrange),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = brandOrange
                        )
                    ) {
                        Text(
                            text = strings.createAccountBtn,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = brandOrange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Footer note
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SignalCellularAlt,
                    contentDescription = null,
                    tint = textMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = strings.limitedConnectivityNote,
                    fontSize = 12.sp,
                    color = textMuted
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
