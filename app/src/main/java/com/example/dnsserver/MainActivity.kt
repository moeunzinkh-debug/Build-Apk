package com.example.dnsserver

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.dnsserver.model.DnsProvider
import com.example.dnsserver.util.DnsManager

class MainActivity : AppCompatActivity() {

  private lateinit var dnsManager: DnsManager
  private lateinit var providerList: ListView

  private val providers = listOf(
    DnsProvider("Google DNS", "dns.google", R.drawable.ic_dns),
    DnsProvider("Quad9", "dns.quad9.net", R.drawable.ic_dns),
    DnsProvider("Cloudflare", "1dot1dot1dot1.cloudflare-dns.com", R.drawable.ic_dns),
    DnsProvider("DNS.SB", "dns.dns.sb", R.drawable.ic_dns),
    DnsProvider("AdGuard", "dns.adguard-dns.com", R.drawable.ic_dns)
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    dnsManager = DnsManager(this)

    val toolbar: Toolbar = findViewById(R.id.toolbar)
    setSupportActionBar(toolbar)
    supportActionBar?.title = "DNS Server"

    providerList = findViewById(R.id.provider_list)
    val adapter = DnsProviderAdapter(this, providers)
    providerList.adapter = adapter

    providerList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
      val provider = providers[position]
      dnsManager.applyPrivateDns(provider.hostname) { success, message ->
        runOnUiThread {
          Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
          if (success) {
            adapter.notifyDataSetChanged()
          }
        }
      }
    }

    val clearBtn: Button = findViewById(R.id.clear_dns_btn)
    clearBtn.setOnClickListener {
      dnsManager.clearPrivateDns { success, message ->
        runOnUiThread {
          Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
          if (success) {
            adapter.notifyDataSetChanged()
          }
        }
      }
    }

    updateSelectionIndicator(adapter)
  }

  private fun updateSelectionIndicator(adapter: DnsProviderAdapter) {
    val currentDns = dnsManager.getCurrentPrivateDns()
    for (i in providers.indices) {
      adapter.setSelected(i, currentDns?.equals(providers[i].hostname, ignoreCase = true) == true)
    }
    adapter.notifyDataSetChanged()
  }

  private inner class DnsProviderAdapter(
    private val context: Context,
    private val items: List<DnsProvider>
  ) : BaseAdapter() {

    private val inflater: LayoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val selectedIndices = mutableSetOf<Int>()

    fun setSelected(position: Int, selected: Boolean) {
      if (selected) selectedIndices.add(position) else selectedIndices.remove(position)
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val view = convertView ?: inflater.inflate(R.layout.item_dns_provider, parent, false)
      val provider = items[position]

      view.findViewById<TextView>(R.id.provider_name).text = provider.name
      view.findViewById<TextView>(R.id.provider_host).text = provider.hostname
      val icon: ImageView = view.findViewById(R.id.provider_icon)
      icon.setImageResource(provider.iconRes)

      val statusView: TextView = view.findViewById(R.id.provider_status)
      val isSelected = selectedIndices.contains(position)
      statusView.text = if (isSelected) "Active" else "Inactive"
      statusView.setTextColor(
        if (isSelected)
          context.resources.getColor(R.color.dns_active, context.theme)
        else
          context.resources.getColor(R.color.dns_inactive, context.theme)
      )

      return view
    }
  }
}
