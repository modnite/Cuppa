package com.modnite.cuppa

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class PrintersTabFragment : Fragment() {

    private var tvHardwareStatus: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_printers_tab, container, false)
        tvHardwareStatus = view.findViewById(R.id.tvHardwareStatus)

        val btnTestPage = view.findViewById<MaterialButton>(R.id.btnTestPage)
        btnTestPage.setOnClickListener {
            // Future implementation: Send test page to printer
        }

        return view
    }

    fun updateHardwareBadge(states: Set<HardwareState>) {
        val tv = tvHardwareStatus ?: return
        if (states.contains(HardwareState.HEAD_OPEN)) {
            tv.text = "● Hardware: Cover Open"
            tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
        } else if (states.contains(HardwareState.OUT_OF_PAPER)) {
            tv.text = "● Hardware: Out of Paper (Red LED)"
            tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        } else if (states.contains(HardwareState.READY)) {
            tv.text = "● Hardware: Ready (Green LED)"
            tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        } else if (states.contains(HardwareState.PRINTING)) {
            tv.text = "● Hardware: Printing..."
            tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
        } else {
            tv.text = "● Hardware: Disconnected / Unknown"
            tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        }
    }
}
