package com.bing.androidvoiceflow.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object CaptureColors {
    val Background = Color(0xFF101118)
    val Surface = Color(0xFF171827)
    val Card = Color(0xFF20212E)
    val CardStrong = Color(0xFF282936)
    val Text = Color(0xFFF4F1FA)
    val Muted = Color(0xFF8D8C98)
    val Purple = Color(0xFF8170D7)
    val PurpleSoft = Color(0xFFB9AEFF)
    val Reading = Color(0xFFD0B86E)
    val Success = Color(0xFF79C995)
    val Border = Color(0xFF393A49)
    val Danger = Color(0xFFC92B22)
    val Warning = Color(0xFFE1B857)
}

internal val CaptureCardShape = RoundedCornerShape(20.dp)
internal val CaptureButtonShape = RoundedCornerShape(18.dp)

private const val CaptureFontScale = 0.92f

@Composable
internal fun CaptureTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val compactDensity = remember(density) {
        Density(density.density, density.fontScale * CaptureFontScale)
    }
    val selectionColors = remember {
        TextSelectionColors(
            handleColor = CaptureColors.PurpleSoft,
            backgroundColor = CaptureColors.Purple.copy(alpha = 0.34f)
        )
    }
    CompositionLocalProvider(
        LocalDensity provides compactDensity,
        LocalTextSelectionColors provides selectionColors
    ) {
        androidx.compose.material3.MaterialTheme(content = content)
    }
}

@Composable
internal fun CaptureSectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = CaptureColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        trailing?.let { Text(it, color = CaptureColors.Muted, fontSize = 12.sp) }
    }
}

@Composable
internal fun CaptureCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptureColors.Card, CaptureCardShape)
            .border(1.dp, CaptureColors.Border, CaptureCardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = { content() }
    )
}

@Composable
internal fun CapturePrimaryButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CaptureButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) CaptureColors.Danger else CaptureColors.Purple,
            contentColor = Color.White,
            disabledContainerColor = CaptureColors.Purple.copy(alpha = 0.38f),
            disabledContentColor = CaptureColors.Text.copy(alpha = 0.45f)
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun RowScope.CaptureSecondaryButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(56.dp),
        shape = CaptureButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) Color(0xFFFFA7A2) else CaptureColors.Text,
            disabledContentColor = CaptureColors.Muted.copy(alpha = 0.45f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, CaptureColors.Border)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun CaptureStatusBand(text: String, warning: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) CaptureColors.Warning.copy(alpha = 0.12f) else CaptureColors.CardStrong,
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Text(
            text,
            color = if (warning) CaptureColors.Warning else CaptureColors.Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}
