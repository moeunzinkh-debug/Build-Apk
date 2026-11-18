package com.example.dnsserver.model

import com.example.dnsserver.R

data class DnsProvider(
    val name: String,
    val hostname: String,
    val iconRes: Int
)

val DNS_PROVIDERS = listOf(
    DnsProvider("Google DNS", "dns.google", R.drawable.ic_dns),
    DnsProvider("Quad9", "dns.quad9.net", R.drawable.ic_dns),
    DnsProvider("Cloudflare", "1dot1dot1dot1.cloudflare-dns.com", R.drawable.ic_dns),
    DnsProvider("DNS.SB", "dns.dns.sb", R.drawable.ic_dns),
    DnsProvider("AdGuard", "dns.adguard-dns.com", R.drawable.ic_dns)
)
