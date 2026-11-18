package com.example.dnsserver.model

import androidx.compose.ui.graphics.Color

data class DnsProvider(
    val id: String,
    val name: String,
    val hostname: String,
    val primaryIp: String,
    val secondaryIp: String,
    val description: String,
    val icon: String,
    val color: Color = Color(0xFF6366F1)
)

val DNS_PROVIDERS = listOf(
    DnsProvider(
        id = "google",
        name = "Google DNS",
        hostname = "dns.google",
        primaryIp = "8.8.8.8",
        secondaryIp = "8.8.4.4",
        description = "លឿន និងស្ថេរភាពខ្ពស់បំផុត",
        icon = "🔵",
        color = Color(0xFF4285F4)
    ),
    DnsProvider(
        id = "quad9",
        name = "Quad9",
        hostname = "dns.quad9.net",
        primaryIp = "9.9.9.9",
        secondaryIp = "149.112.112.112",
        description = "លឿន + ការពារមេរោគល្អ",
        icon = "🛡️",
        color = Color(0xFF059669)
    ),
    DnsProvider(
        id = "cloudflare",
        name = "Cloudflare",
        hostname = "one.one.one.one",
        primaryIp = "1.1.1.1",
        secondaryIp = "1.0.0.1",
        description = "លឿនខ្លាំង ឯកជនភាពល្អ",
        icon = "☁️",
        color = Color(0xFFEF4444)
    ),
    DnsProvider(
        id = "dns_sb",
        name = "DNS.SB",
        hostname = "dns.sb",
        primaryIp = "1.1.1.1",
        secondaryIp = "1.0.0.1",
        description = "លឿនណាស់ និងឯកជនភាពខ្ពស៏",
        icon = "⚡",
        color = Color(0xFF8B5CF6)
    ),
    DnsProvider(
        id = "adguard",
        name = "AdGuard DNS",
        hostname = "dns.adguard.com",
        primaryIp = "94.140.14.14",
        secondaryIp = "94.140.15.15",
        description = "លឿន + បិទការផ្សាយពាណិជ្ជកម្ម",
        icon = "🚫",
        color = Color(0xFF0EA5E9)
    )
)
