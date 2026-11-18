package com.example.dnsserver.ui.theme

import androidx.compose.ui.graphics.Color

val DarkColorScheme = android.graphics.Color(0xFF020617).toComposeColor()

private fun Int.toComposeColor() = androidx.compose.ui.graphics.Color(this)

val PrimaryGradientStart = Color(0xFF6366F1)   // Indigo
val PrimaryGradientEnd = Color(0xFFEC4899)     // Pink
val SurfaceCard = Color(0xFF1E293B)
val SurfaceCardHover = Color(0xFF334155)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)
val BorderColor = Color(0xFF334155)
val SuccessGreen = Color(0xFF10B981)
val WarningOrange = Color(0xFFF59E0B)
