package com.example.dnsserver.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DnsServerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = PrimaryGradientStart,
            primaryContainer = PrimaryGradientEnd,
            secondary = Color(0xFFEC4899),
            surface = SurfaceCard,
            background = Color(0xFF020617),
        ),
        typography = Typography,
        content = content
    )
}

@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(PrimaryGradientStart, PrimaryGradientEnd)
) {
    val brush = Brush.linearGradient(
        colors = colors,
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )
    Surface(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
        color = Color.Transparent,
        contentColor = Color.Unspecified
    ) {
        androidx.compose.foundation.Background(brush)
    }
}

@Composable
fun DnsCard(
    modifier: Modifier = Modifier,
    provider: com.example.dnsserver.model.DnsProvider,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) provider.color else BorderColor
    val backgroundColor = if (isSelected) provider.color.copy(alpha = 0.15f) else SurfaceCard

    androidx.compose.material3.Card(
        modifier = modifier
            .clipToBounds()
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedShape(16.dp)
            )
            .background(backgroundColor, shape = RoundedShape(16.dp))
            .clickable { onClick() },
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedShape(16.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.ui.graphics.vector.ImageVector.Default,
                contentDescription = null
            )
            androidx.compose.material3.Text(
                text = provider.icon,
                fontSize = 32.sp,
                modifier = androidx.compose.ui.Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                provider.color,
                                provider.color.copy(alpha = 0.6f)
                            )
                        ),
                        shape = RoundedShape(12.dp)
                    ),
                color = Color.White
            )
            androidx.compose.foundation.layout.Spacer(
                modifier = androidx.compose.ui.Modifier.width(12.dp)
            )
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.weight(1f)
            ) {
                androidx.compose.material3.Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                androidx.compose.material3.Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1
                )
                androidx.compose.material3.Text(
                    text = "${provider.primaryIp}  •  ${provider.secondaryIp}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
            if (isSelected) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material3.Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = provider.color
                )
            }
        }
    }
}
