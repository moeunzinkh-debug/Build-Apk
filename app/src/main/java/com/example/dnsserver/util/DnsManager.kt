package com.example.dnsserver.util

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

class DnsManager(private val context: Context) {

  private const val TAG = "DnsManager"

  // Constants for Private DNS (API 28+)
  private const val PRIVATE_DNS_MODE = "private_dns_mode"
  private const val PRIVATE_DNS_SPECIFY = "private_dns_specify"
  private const val MODE_OFF = 0
  private const val MODE_SPECIFY = 1

  fun applyPrivateDns(hostname: String, callback: (success: Boolean, message: String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      callback(false, "Private DNS not supported on this Android version")
      return
    }

    try {
      val contentResolver: ContentResolver = context.contentResolver
      Settings.Global.putInt(contentResolver, PRIVATE_DNS_MODE, MODE_SPECIFY)
      Settings.Global.putString(contentResolver, PRIVATE_DNS_SPECIFY, hostname)
      Log.i(TAG, "Private DNS set to: $hostname")
      callback(true, "DNS set to $hostname")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set Private DNS", e)
      callback(false, "Failed to set DNS: ${e.message}")
    }
  }

  fun clearPrivateDns(callback: (success: Boolean, message: String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      callback(false, "Private DNS not supported on this Android version")
      return
    }

    try {
      val contentResolver: ContentResolver = context.contentResolver
      Settings.Global.putInt(contentResolver, PRIVATE_DNS_MODE, MODE_OFF)
      Log.i(TAG, "Private DNS cleared")
      callback(true, "DNS cleared")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to clear Private DNS", e)
      callback(false, "Failed to clear DNS: ${e.message}")
    }
  }

  fun getCurrentPrivateDns(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return null
    }
    return Settings.Global.getString(context.contentResolver, PRIVATE_DNS_SPECIFY)
  }

  fun isWriteSettingsGranted(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return Settings.System.canWrite(context)
    }
    return true
  }

  fun getWriteSettingsIntent(): android.content.Intent {
    return android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS.let { intent ->
      intent.data = android.net.Uri.parse("package:${context.packageName}")
      intent
    }
  }
}
