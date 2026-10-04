package org.coresense.itantra.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

@Composable
fun PermissionRationaleDialog(
    permissionName: String,
    rationale: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = PrimaryNeonGreen
            )
        },
        title = {
            Text(text = "Permission Required: $permissionName", fontWeight = FontWeight.Bold)
        },
        text = {
            Text(text = rationale)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen)
            ) {
                Text(text = "Grant Permission", color = DarkSurface)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
            ) {
                Text(text = "Cancel")
            }
        }
    )
}
