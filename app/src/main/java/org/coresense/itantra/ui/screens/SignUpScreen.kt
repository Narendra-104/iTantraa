package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.identity.Department
import org.coresense.itantra.identity.Role
import org.coresense.itantra.storage.UserProfileEntity
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.i18n.AppLocalization

@Composable
fun SignUpScreen(
    viewModel: MainViewModel,
    onCreateAccountSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val currentLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(currentLang) { AppLocalization.getStrings(currentLang) }

    var fullName by remember { mutableStateOf("") }
    var aadhar by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var mobileNumber by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }

    val bloodGroupOptions = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-", "Unknown")
    var bloodGroup by remember { mutableStateOf("Unknown") }
    var bloodGroupMenuExpanded by remember { mutableStateOf(false) }

    val genderOptions = listOf(strings.maleOption, strings.femaleOption, strings.otherOption)
    var gender by remember { mutableStateOf(strings.maleOption) }
    var genderMenuExpanded by remember { mutableStateOf(false) }

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPassword by remember { mutableStateOf("") }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    var isDepartmentMember by remember { mutableStateOf(false) }
    val departmentOptions = listOf("NDRF", "Medical", "Police", "Fire & Disaster", "Railway", "Civil Defence")
    var selectedDepartment by remember { mutableStateOf("NDRF") }
    var departmentMenuExpanded by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val brandNavy = Color(0xFF0F1E36)
    val brandOrange = Color(0xFFC07D3E)
    val textMuted = Color(0xFF64748B)
    val borderColor = Color(0xFFE2E8F0)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // App Brand Header
            Text(
                text = "iTantra",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = brandNavy
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = "Stay Connected. ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF334155)
                )
                Text(
                    text = "Stay Safe.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = brandOrange
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Title & Subtitle
            Text(
                text = strings.createAccountTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = brandNavy
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = strings.createAccountSubtitle,
                fontSize = 12.sp,
                color = textMuted
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Form Container Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Full Name
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it; errorMessage = null },
                        label = { Text(strings.fullNameLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = textMuted)
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

                    // Aadhar Number
                    OutlinedTextField(
                        value = aadhar,
                        onValueChange = { aadhar = it },
                        label = { Text(strings.aadharLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Badge, contentDescription = null, tint = textMuted)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

                    // Email
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(strings.emailLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null, tint = textMuted)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
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

                    // Mobile number
                    OutlinedTextField(
                        value = mobileNumber,
                        onValueChange = { mobileNumber = it },
                        label = { Text(strings.mobileLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = textMuted)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
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

                    // Address
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(strings.addressLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Home, contentDescription = null, tint = textMuted)
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

                    // Birth Date
                    OutlinedTextField(
                        value = birthDate,
                        onValueChange = { birthDate = it },
                        label = { Text(strings.birthDateLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = textMuted)
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

                    // Side-by-Side: Blood Group & Gender
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Blood Group Dropdown
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = bloodGroup,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(strings.bloodGroupLabel, fontSize = 12.sp) },
                                trailingIcon = {
                                    IconButton(onClick = { bloodGroupMenuExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { bloodGroupMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = brandNavy,
                                    unfocusedBorderColor = borderColor,
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black
                                )
                            )
                            DropdownMenu(
                                expanded = bloodGroupMenuExpanded,
                                onDismissRequest = { bloodGroupMenuExpanded = false }
                            ) {
                                bloodGroupOptions.forEach { bg ->
                                    DropdownMenuItem(
                                        text = { Text(bg) },
                                        onClick = {
                                            bloodGroup = bg
                                            bloodGroupMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Gender Dropdown
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = gender,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(strings.genderLabel, fontSize = 12.sp) },
                                trailingIcon = {
                                    IconButton(onClick = { genderMenuExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { genderMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = brandNavy,
                                    unfocusedBorderColor = borderColor,
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black
                                )
                            )
                            DropdownMenu(
                                expanded = genderMenuExpanded,
                                onDismissRequest = { genderMenuExpanded = false }
                            ) {
                                genderOptions.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt) },
                                        onClick = {
                                            gender = opt
                                            genderMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Create Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text(strings.createPasswordLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = textMuted)
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

                    // Confirm Password
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMessage = null },
                        label = { Text(strings.confirmPasswordLabel, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = textMuted)
                        },
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password",
                                    tint = textMuted
                                )
                            }
                        },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
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

                    Spacer(modifier = Modifier.height(4.dp))

                    // Role Selection: "I am a"
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = strings.roleSelectionTitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF334155)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // General User Option
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clickable { isDepartmentMember = false },
                                shape = RoundedCornerShape(8.dp),
                                color = if (!isDepartmentMember) brandNavy else Color(0xFFF1F5F9),
                                border = if (!isDepartmentMember) null else androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = strings.generalUserRole,
                                        fontSize = 13.sp,
                                        fontWeight = if (!isDepartmentMember) FontWeight.Bold else FontWeight.Medium,
                                        color = if (!isDepartmentMember) Color.White else Color(0xFF475569)
                                    )
                                }
                            }

                            // Department Member Option
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clickable { isDepartmentMember = true },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isDepartmentMember) brandNavy else Color(0xFFF1F5F9),
                                border = if (isDepartmentMember) null else androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = strings.departmentMemberRole,
                                        fontSize = 13.sp,
                                        fontWeight = if (isDepartmentMember) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isDepartmentMember) Color.White else Color(0xFF475569)
                                    )
                                }
                            }
                        }

                        // Department Dropdown if Department Member is selected
                        if (isDepartmentMember) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = selectedDepartment,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(strings.selectDepartmentLabel, fontSize = 12.sp) },
                                    trailingIcon = {
                                        IconButton(onClick = { departmentMenuExpanded = true }) {
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { departmentMenuExpanded = true },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = brandNavy,
                                        unfocusedBorderColor = borderColor,
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black
                                    )
                                )
                                DropdownMenu(
                                    expanded = departmentMenuExpanded,
                                    onDismissRequest = { departmentMenuExpanded = false }
                                ) {
                                    departmentOptions.forEach { dept ->
                                        DropdownMenuItem(
                                            text = { Text(dept) },
                                            onClick = {
                                                selectedDepartment = dept
                                                departmentMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Error Message
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFDC2626),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Create Account Button with Sparkle Icon
                    Button(
                        onClick = {
                            if (fullName.isBlank()) {
                                errorMessage = strings.nameRequiredError
                                return@Button
                            }
                            if (password.isBlank()) {
                                errorMessage = "Please enter a password"
                                return@Button
                            }
                            if (password != confirmPassword) {
                                errorMessage = strings.passwordsDoNotMatchError
                                return@Button
                            }

                            val resolvedUserId = viewModel.identityRepository.getDeviceId()
                            val cleanName = fullName.trim()
                            val profile = UserProfileEntity(
                                userId = resolvedUserId,
                                name = cleanName,
                                aadhar = aadhar.trim(),
                                email = email.trim(),
                                mobileNumber = mobileNumber.trim(),
                                address = address.trim(),
                                birthDate = birthDate.trim(),
                                bloodGroup = bloodGroup.ifBlank { "Unknown" },
                                gender = gender.trim()
                            )
                            val role = if (isDepartmentMember) Role.DEPARTMENT else Role.USER
                            val dept = if (isDepartmentMember) {
                                when (selectedDepartment.trim()) {
                                    "NDRF", "NDRF_SEARCH_RESCUE" -> Department.NDRF_SEARCH_RESCUE
                                    "Medical", "MEDICAL" -> Department.MEDICAL
                                    "Police", "POLICE" -> Department.POLICE
                                    "Fire & Disaster", "FIRE", "FIRE_DISASTER" -> Department.FIRE_DISASTER
                                    "Railway", "RAILWAY", "RAILWAY_COMMISSION" -> Department.RAILWAY_COMMISSION
                                    "Civil Defence", "CIVIL", "CIVIL_DEFENCE" -> Department.CIVIL_DEFENCE
                                    else -> Department.entries.firstOrNull { it.name.equals(selectedDepartment.trim(), ignoreCase = true) }
                                }
                            } else null

                            viewModel.saveUserProfile(profile, role = role, departmentId = dept?.id)

                            // Save credentials into IdentityRepository
                            viewModel.identityRepository.saveAccount(password.trim())

                            viewModel.identityRepository.saveIdentity(
                                name = cleanName,
                                role = role,
                                department = dept,
                                departmentId = dept?.id
                            )
                            viewModel.callsign.value = cleanName

                            onCreateAccountSuccess()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = brandNavy)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFFFFD700),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.createAccountBtn,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Footer link
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = strings.alreadyHaveAccount,
                    fontSize = 13.sp,
                    color = textMuted
                )
                Text(
                    text = strings.loginLink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = brandOrange,
                    modifier = Modifier.clickable { onNavigateToLogin() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
