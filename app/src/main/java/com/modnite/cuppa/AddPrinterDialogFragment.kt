package com.modnite.cuppa

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class AddPrinterDialogFragment : BottomSheetDialogFragment() {

    data class DiscoveredDevice(
        val name: String,
        val details: String,
        val uri: String
    )

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredPrinters = mutableListOf<DiscoveredDevice>()
    private var adapter: DiscoveredAdapter? = null

    companion object {
        fun newInstance(): AddPrinterDialogFragment {
            return AddPrinterDialogFragment()
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_add_printer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvDiscoveredPrinters = view.findViewById<RecyclerView>(R.id.rvDiscoveredPrinters)
        val tvDiscoveryStatus = view.findViewById<TextView>(R.id.tvDiscoveryStatus)
        val btnCloseAddPrinter = view.findViewById<MaterialButton>(R.id.btnCloseAddPrinter)

        rvDiscoveredPrinters.layoutManager = LinearLayoutManager(requireContext())
        adapter = DiscoveredAdapter(discoveredPrinters) { device ->
            val newPrinter = CupsPrinter(
                id = device.name.replace(" ", "_"),
                name = device.name,
                uri = device.uri
            )
            CupsPrinterRegistry.addPrinter(newPrinter)
            Toast.makeText(requireContext(), "Added printer: ${newPrinter.name}", Toast.LENGTH_SHORT).show()
            dismiss()
        }
        rvDiscoveredPrinters.adapter = adapter

        // 1. Scan USB Devices
        scanUsbDevices()

        // 2. Scan Network Printers via mDNS
        scanNetworkPrinters(tvDiscoveryStatus)

        btnCloseAddPrinter.setOnClickListener { dismiss() }
    }

    private fun scanUsbDevices() {
        try {
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values.toList()

            for (dev in devices) {
                val friendlyName = when {
                    dev.vendorId == 2501 -> "Rollo X1038 Thermal Printer"
                    !dev.productName.isNullOrBlank() -> dev.productName!!
                    !dev.manufacturerName.isNullOrBlank() -> "${dev.manufacturerName} Printer"
                    else -> "USB Printer (VID: 0x${dev.vendorId.toString(16).uppercase()})"
                }

                val devUri = "usb://${dev.vendorId}/${dev.productId}"
                val details = "USB Connection | VID=0x${dev.vendorId.toString(16).uppercase()}"

                if (discoveredPrinters.none { it.uri == devUri }) {
                    discoveredPrinters.add(DiscoveredDevice(friendlyName, details, devUri))
                }
            }
            adapter?.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    private fun scanNetworkPrinters(statusTv: TextView) {
        try {
            nsdManager = requireContext().getSystemService(Context.NSD_SERVICE) as NsdManager
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    activity?.runOnUiThread {
                        statusTv.text = "Scanning USB bus and Wi-Fi network..."
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val name = serviceInfo.serviceName
                    val devUri = "ipp://${serviceInfo.serviceName}._ipp._tcp.local/"
                    val details = "Network IPP / AirPrint Printer"

                    activity?.runOnUiThread {
                        if (discoveredPrinters.none { it.name == name || it.uri == devUri }) {
                            discoveredPrinters.add(DiscoveredDevice(name, details, devUri))
                            adapter?.notifyDataSetChanged()
                            statusTv.text = "Discovered ${discoveredPrinters.size} printer(s):"
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdManager?.discoverServices("_ipp._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        try {
            if (discoveryListener != null) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
                discoveryListener = null
            }
        } catch (_: Exception) {}
        super.onDestroyView()
    }

    private class DiscoveredAdapter(
        private val devices: List<DiscoveredDevice>,
        private val onSelect: (DiscoveredDevice) -> Unit
    ) : RecyclerView.Adapter<DiscoveredAdapter.DevViewHolder>() {

        class DevViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(android.R.id.text1)
            val tvDetails: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DevViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return DevViewHolder(view)
        }

        override fun onBindViewHolder(holder: DevViewHolder, position: Int) {
            val dev = devices[position]
            holder.tvName.text = dev.name
            holder.tvDetails.text = dev.details
            holder.itemView.setOnClickListener { onSelect(dev) }
        }

        override fun getItemCount(): Int = devices.size
    }
}
