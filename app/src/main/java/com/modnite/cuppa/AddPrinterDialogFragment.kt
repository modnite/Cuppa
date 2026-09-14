package com.modnite.cuppa

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AddPrinterDialogFragment : DialogFragment() {

    companion object {
        fun newInstance(): AddPrinterDialogFragment {
            return AddPrinterDialogFragment()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val scale = if (isPortrait) 0.98 else 0.94
            val width = (resources.displayMetrics.widthPixels * scale).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_printer, null)

        val rvDiscoveredPrinters = view.findViewById<RecyclerView>(R.id.rvDiscoveredPrinters)
        val tvDiscoveryStatus = view.findViewById<TextView>(R.id.tvDiscoveryStatus)
        val btnCloseAddPrinter = view.findViewById<MaterialButton>(R.id.btnCloseAddPrinter)

        rvDiscoveredPrinters.layoutManager = LinearLayoutManager(requireContext())

        val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values.toList()

        if (devices.isEmpty()) {
            tvDiscoveryStatus.text = "No additional USB/Network printers found on bus."
        } else {
            tvDiscoveryStatus.text = "Found ${devices.size} device(s) on USB bus:"
            rvDiscoveredPrinters.adapter = DiscoveredAdapter(devices) { dev ->
                val newPrinter = CupsPrinter(
                    id = "USB_${dev.vendorId}_${dev.productId}",
                    name = "USB Printer (${dev.deviceName})",
                    uri = "usb://${dev.vendorId}/${dev.productId}"
                )
                CupsPrinterRegistry.addPrinter(newPrinter)
                Toast.makeText(requireContext(), "Added printer: ${newPrinter.name}", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }

        btnCloseAddPrinter.setOnClickListener { dismiss() }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    private class DiscoveredAdapter(
        private val devices: List<UsbDevice>,
        private val onSelect: (UsbDevice) -> Unit
    ) : RecyclerView.Adapter<DiscoveredAdapter.DevViewHolder>() {

        class DevViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DevViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return DevViewHolder(view)
        }

        override fun onBindViewHolder(holder: DevViewHolder, position: Int) {
            val dev = devices[position]
            holder.tvName.text = "Device: ${dev.deviceName} | VID=0x${dev.vendorId.toString(16).uppercase()}"
            holder.itemView.setOnClickListener { onSelect(dev) }
        }

        override fun getItemCount(): Int = devices.size
    }
}
