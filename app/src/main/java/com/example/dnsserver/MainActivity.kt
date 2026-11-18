package com.example.dnsserver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.dnsserver.model.DnsProvider
import com.example.dnsserver.util.DnsManager

class MainActivity : AppCompatActivity() {

  private lateinit var dnsManager: DnsManager
  private lateinit var dnsSpinner: Spinner
  private lateinit var toggleSwitch: Switch
  private lateinit var statusText: TextView
  private lateinit var currentHostText: TextView
  private lateinit var subtitleText: TextView
  private lateinit var statusIcon: ImageView
  private lateinit var apiWarning: TextView
  private lateinit var settingsBtn: ImageButton
  private lateinit var themeBtn: ImageButton

  private val providers = listOf(
    DnsProvider("Google DNS", "dns.google", R.drawable.ic_dns),
    DnsProvider("Quad9", "dns.quad9.net", R.drawable.ic_dns),
    DnsProvider("Cloudflare", "1dot1dot1dot1.cloudflare-dns.com", R.drawable.ic_dns),
    DnsProvider("DNS.SB", "dns.dns.sb", R.drawable.ic_dns),
    DnsProvider("AdGuard", "dns.adguard-dns.com", R.drawable.ic_dns)
  )

  private var isDnsOn = false
  private var isInitializing = true
  private val REQUEST_WRITE_SETTINGS = 1001

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      setContentView(R.layout.activity_main)

      // Hide action bar - use custom header
      supportActionBar?.hide()

      dnsManager = DnsManager(this)

      dnsSpinner = findViewById(R.id.dns_spinner)
      toggleSwitch = findViewById(R.id.toggle_switch)
      statusText = findViewById(R.id.dns_status_text)
      currentHostText = findViewById(R.id.current_dns_host)
      subtitleText = findViewById(R.id.dns_subtitle)
      statusIcon = findViewById(R.id.status_icon)
      apiWarning = findViewById(R.id.api_warning)
      settingsBtn = findViewById(R.id.settings_btn)
      themeBtn = findViewById(R.id.theme_btn)

      // Setup Spinner (drop-down menu)
      val spinnerAdapter = ArrayAdapter(
        this,
        R.layout.spinner_item,
        providers.map { it.name }
      )
      spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
      dnsSpinner.adapter = spinnerAdapter

      // Check API level - Private DNS requires API 28+
      if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
        showApiWarning()
      } else {
        hideApiWarning()
        checkWriteSettingsPermission()
      }

      // Settings button click - open write settings permission page
      settingsBtn.setOnClickListener {
        try {
          openWriteSettingsPage()
        } catch (e: Exception) {
          Toast.makeText(this@MainActivity, "Unable to open settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }

      // Theme button click
      themeBtn.setOnClickListener {
        Toast.makeText(this@MainActivity, "Theme feature coming soon", Toast.LENGTH_SHORT).show()
      }

      // Toggle switch change listener
      toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
        try {
          onToggleChanged(isChecked)
        } catch (e: Exception) {
          Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
          toggleSwitch.isChecked = false
        }
      }

      // Spinner selection change
      dnsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
          try {
            val provider = providers[position]
            currentHostText.text = provider.hostname
            if (!isDnsOn) {
              statusText.text = getString(R.string.dns_off)
              statusText.setTextColor(resources.getColor(android.R.color.white, theme))
            }
          } catch (e: Exception) {
            // Ignore spinner errors
          }
        }

        override fun onNothingSelected(parent: AdapterView<*>) {}
      }

      isInitializing = false
    } catch (e: Exception) {
      Toast.makeText(this, "App initialization error: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  override fun onResume() {
    super.onResume()
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        checkWriteSettingsPermission()
        loadCurrentDnsState()
      }
    } catch (e: Exception) {
      Toast.makeText(this, "Resume error: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  private fun showApiWarning() {
    apiWarning.visibility = View.VISIBLE
    toggleSwitch.isEnabled = false
    statusText.text = getString(R.string.api_not_supported)
    statusText.setTextColor(resources.getColor(android.R.color.white, theme))
    subtitleText.text = ""
  }

  private fun hideApiWarning() {
    apiWarning.visibility = View.GONE
  }

  private fun checkWriteSettingsPermission() {
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        val canWrite = Settings.System.canWrite(this)
        if (!canWrite) {
          toggleSwitch.isEnabled = false
          statusText.text = getString(R.string.permission_required)
          statusText.setTextColor(resources.getColor(android.R.color.white, theme))
          subtitleText.text = getString(R.string.grant_permission_to_use)
          apiWarning.visibility = View.VISIBLE
          apiWarning.text = getString(R.string.write_settings_permission_needed)
          apiWarning.setTextColor(resources.getColor(android.R.color.white, theme))
        } else {
          toggleSwitch.isEnabled = true
          apiWarning.visibility = View.GONE
        }
      } else {
        toggleSwitch.isEnabled = true
      }
    } catch (e: Exception) {
      toggleSwitch.isEnabled = false
      statusText.text = getString(R.string.permission_required)
    }
  }

  private fun isWriteSettingsGranted(): Boolean {
    return try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        Settings.System.canWrite(this)
      } else {
        true
      }
    } catch (e: Exception) {
      false
    }
  }

  private fun openWriteSettingsPage() {
    try {
      val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
        data = Uri.parse("package:${packageName}")
      }
      startActivity(intent)
    } catch (e: Exception) {
      // Fallback: open app info settings
      val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${packageName}")
      }
      startActivity(fallbackIntent)
    }
  }

  private fun onToggleChanged(isChecked: Boolean) {
    if (isInitializing) return

    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
      Toast.makeText(this@MainActivity, R.string.api_not_supported, Toast.LENGTH_LONG).show()
      toggleSwitch.isChecked = false
      return
    }

    if (!isWriteSettingsGranted()) {
      Toast.makeText(this@MainActivity, R.string.permission_required, Toast.LENGTH_LONG).show()
      toggleSwitch.isChecked = false
      openWriteSettingsPage()
      return
    }

    if (isChecked) {
      // Turn ON
      val selectedPosition = dnsSpinner.selectedItemPosition
      val selectedProvider = providers[selectedPosition]
      dnsManager.applyPrivateDns(selectedProvider.hostname) { success, message ->
        runOnUiThread {
          try {
            if (success) {
              isDnsOn = true
              updateUI(on = true, provider = selectedProvider)
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } else {
              toggleSwitch.isChecked = false
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
          } catch (e: Exception) {
            // Ignore UI update errors
          }
        }
      }
    } else {
      // Turn OFF
      dnsManager.clearPrivateDns { success, message ->
        runOnUiThread {
          try {
            if (success) {
              isDnsOn = false
              updateUI(on = false, provider = null)
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } else {
              toggleSwitch.isChecked = true
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
          } catch (e: Exception) {
            // Ignore UI update errors
          }
        }
      }
    }
  }

  private fun loadCurrentDnsState() {
    try {
      val currentDns = dnsManager.getCurrentPrivateDns()
      if (currentDns != null && currentDns.isNotEmpty()) {
        isDnsOn = true
        toggleSwitch.isChecked = true
        // Find matching provider
        var matchedIndex = 0
        for (i in providers.indices) {
          if (providers[i].hostname.equals(currentDns, ignoreCase = true)) {
            matchedIndex = i
            break
          }
        }
        dnsSpinner.setSelection(matchedIndex)
        updateUI(on = true, provider = providers[matchedIndex])
      } else {
        isDnsOn = false
        toggleSwitch.isChecked = false
        updateUI(on = false, provider = null)
      }
    } catch (e: Exception) {
      isDnsOn = false
      toggleSwitch.isChecked = false
      updateUI(on = false, provider = null)
    }
  }

  private fun updateUI(on: Boolean, provider: DnsProvider?) {
    try {
      isDnsOn = on

      if (on) {
        statusText.text = getString(R.string.dns_on)
        statusText.setTextColor(resources.getColor(R.color.dns_active, theme))
        subtitleText.text = "DNS server ដំណើរការរួចរាល់"
        subtitleText.setTextColor(resources.getColor(R.color.dns_active, theme))
        statusIcon.setImageResource(R.drawable.ic_dns_on)
        currentHostText.text = provider?.hostname ?: ""
        currentHostText.setTextColor(resources.getColor(android.R.color.white, theme))
      } else {
        statusText.text = getString(R.string.dns_off)
        statusText.setTextColor(resources.getColor(android.R.color.white, theme))
        subtitleText.text = getString(R.string.tap_to_toggle)
        subtitleText.setTextColor(resources.getColor(android.R.color.white, theme))
        statusIcon.setImageResource(R.drawable.ic_dns_off)
        currentHostText.text = getString(R.string.no_dns_selected)
        currentHostText.setTextColor(resources.getColor(android.R.color.white, theme))
      }
    } catch (e: Exception) {
      // Ignore UI update errors
    }
  }
}
