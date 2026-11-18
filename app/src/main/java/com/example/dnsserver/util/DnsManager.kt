package com.example.dnsserver.util

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.dnsserver.model.DnsProvider

object DnsManager {

    private const val TAG = "DnsManager"

    fun applyDns(context: Context, provider: DnsProvider): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return applyPrivateDns(context, provider.hostname)
        } else {
            Log.w(TAG, "Private DNS not supported on API < 28")
            return false
        }
    }

    private fun applyPrivateDns(context: Context, hostname: String): Boolean {
        val contentResolver: ContentResolver = context.contentResolver
        return try {
            Settings.Global.putInt(
                contentResolver,
                Settings.Global.PRIVATE_DNS_MODE,
                Settings.Global.PRIVATE_DNS_MODE_SPECIFY
            )
            Settings.Global.putString(
                contentResolver,
                Settings.Global.PRIVATE_DNS_SPECIFY,
                hostname
            )
            Log.i(TAG, "Private DNS set to: $hostname")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Private DNS", e)
            false
        }
    }

    fun clearDns(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val contentResolver: ContentResolver = context.contentResolver
                Settings.Global.putInt(
                    contentResolver,
                    Settings.Global.PRIVATE_DNS_MODE,
                    Settings.Global.PRIVATE_DNS_MODE_OFF
                )
                Log.i(TAG, "Private DNS cleared")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear Private DNS", e)
                false
            }
        } else {
            false
        }
    }

    fun getCurrentDnsMode(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.PRIVATE_DNS_MODE,
                Settings.Global.PRIVATE_DNS_MODE_OFF
            )
        } else {
            Settings.Global.PRIVATE_DNS_MODE_OFF
        }
    }

    fun getCurrentDnsHostname(context: Context): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Settings.Global.getString(
                context.contentResolver,
                Settings.Global.PRIVATE_DNS_SPECIFY
            )
        } else {
            null
        }
    }

    fun isWriteSettingsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    fun getWriteSettingsIntent(context: Context): android.content.Intent {
        return android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS
            .let { intent ->
                intent.data = android.net.Uri.parse("package:${context.packageName}")
                intent
            }
    }

    fun getConnectivityManager(context: Context): ConnectivityManager? {
        return context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    }
}
