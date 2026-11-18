package com.example.dnsserver

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.dnsserver.model.DnsProvider
import com.example.dnsserver.util.DnsManager

class MainActivity : AppCompatActivity() {

  private lateinit var dnsManager: DnsManager
  private lateinit var dnsSpinner: Spinner
  private lateinit var toggleBtn: Button
  private lateinit var statusText: TextView
  private lateinit var currentHostText: TextView
  private lateinit var apiWarning: TextView

  private val providers = listOf(
    DnsProvider("Google DNS", "dns.google", R.drawable.ic_dns),
    DnsProvider("Quad9", "dns.quad9.net", R.drawable.ic_dns),
    DnsProvider("Cloudflare", "1dot1dot1dot1.cloudflare-dns.com", R.drawable.ic_dns),
    DnsProvider("DNS.SB", "dns.dns.sb", R.drawable.ic_dns),
    DnsProvider("AdGuard", "dns.adguard-dns.com", R.drawable.ic_dns)
  )

  private var isDnsOn = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
    setSupportActionBar(toolbar)
    supportActionBar?.title = "DNS Server"

    dnsManager = DnsManager(this)

    dnsSpinner = findViewById(R.id.dns_spinner)
    toggleBtn = findViewById(R.id.toggle_dns_btn)
    statusText = findViewById(R.id.dns_status)
    currentHostText = findViewById(R.id.current_dns_host)
    apiWarning = findViewById(R.id.api_warning)

    // Setup Spinner (drop-down menu)
    val spinnerAdapter = ArrayAdapter(
      this,
      android.R.layout.simple_spinner_item,
      providers.map { it.name }
    )
    spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    dnsSpinner.adapter = spinnerAdapter

    // Check API level - Private DNS requires API 28+
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
      apiWarning.visibility = View.VISIBLE
      toggleBtn.isEnabled = false
      statusText.text = getString(R.string.api_not_supported)
      statusText.setTextColor(resources.getColor(R.color.dns_inactive, theme))
    } else {
      apiWarning.visibility = View.GONE
      loadCurrentDnsState()
    }

    // Toggle button click
    toggleBtn.setOnClickListener {
      if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
        Toast.makeText(this, R.string.api_not_supported, Toast.LENGTH_LONG).show()
        return@setOnClickListener
      }

      val selectedPosition = dnsSpinner.selectedItemPosition
      val selectedProvider = providers[selectedPosition]

      if (!isDnsOn) {
        // Turn ON
        dnsManager.applyPrivateDns(selectedProvider.hostname) { success, message ->
          runOnUiThread {
            if (success) {
              isDnsOn = true
              updateUI(on = true, provider = selectedProvider)
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } else {
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
          }
        }
      } else {
        // Turn OFF
        dnsManager.clearPrivateDns { success, message ->
          runOnUiThread {
            if (success) {
              isDnsOn = false
              updateUI(on = false, provider = null)
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } else {
              Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
          }
        }
      }
    }

    // Spinner selection change - update host display
    dnsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
        val provider = providers[position]
        currentHostText.text = provider.hostname
        if (!isDnsOn) {
          statusText.text = getString(R.string.dns_off)
          statusText.setTextColor(resources.getColor(R.color.dns_inactive, theme))
        }
      }

      override fun onNothingSelected(parent: AdapterView<*>) {}
    }
  }

  private fun loadCurrentDnsState() {
    val currentDns = dnsManager.getCurrentPrivateDns()
    if (currentDns != null && currentDns.isNotEmpty()) {
      isDnsOn = true
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
      updateUI(on = false, provider = null)
    }
  }

  private fun updateUI(on: Boolean, provider: DnsProvider?) {
    isDnsOn = on

    if (on) {
      toggleBtn.setText(R.string.dns_on)
      statusText.text = getString(R.string.dns_on)
      statusText.setTextColor(resources.getColor(R.color.dns_active, theme))
      toggleBtn.isEnabled = true
      currentHostText.text = provider?.hostname ?: ""
      currentHostText.setTextColor(resources.getColor(R.color.text_primary, theme))
    } else {
      toggleBtn.setText(R.string.dns_off)
      statusText.text = getString(R.string.dns_off)
      statusText.setTextColor(resources.getColor(R.color.dns_inactive, theme))
      toggleBtn.isEnabled = true
      currentHostText.text = getString(R.string.no_dns_selected)
      currentHostText.setTextColor(resources.getColor(R.color.text_muted, theme))
    }
  }
}
