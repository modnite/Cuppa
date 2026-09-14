package com.modnite.cuppa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class AdminTabFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_tab, container, false)
        val prefs = requireContext().getSharedPreferences("rollo_prefs", Context.MODE_PRIVATE)

        val switchServer = view.findViewById<MaterialSwitch>(R.id.switchServer)
        val switchHoldNetworkJobs = view.findViewById<MaterialSwitch>(R.id.switchHoldNetworkJobs)
        val switchRawPort9100 = view.findViewById<MaterialSwitch>(R.id.switchRawPort9100)
        val rgAppTheme = view.findViewById<RadioGroup>(R.id.rgAppTheme)

        val isServerRunning = PrintServerService.isServerRunning
        switchServer.isChecked = isServerRunning

        val holdNetwork = prefs.getBoolean("PREF_HOLD_NETWORK_JOBS", false)
        val rawPort9100 = prefs.getBoolean("PREF_RAW_PORT_9100", true)
        switchHoldNetworkJobs.isChecked = holdNetwork
        switchRawPort9100.isChecked = rawPort9100

        switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                (activity as? MainActivity)?.startIppServer()
            } else {
                (activity as? MainActivity)?.stopIppServer()
            }
        }

        switchHoldNetworkJobs.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("PREF_HOLD_NETWORK_JOBS", isChecked).apply()
        }

        switchRawPort9100.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("PREF_RAW_PORT_9100", isChecked).apply()
        }

        val btnDiagnostics = view.findViewById<Button>(R.id.btnDiagnostics)
        btnDiagnostics.setOnClickListener {
            Toast.makeText(requireContext(), "Running USB hardware scan...", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.printManager?.runPrinterDiagnosticsAsync()
        }

        val btnViewCache = view.findViewById<Button>(R.id.btnViewCache)
        btnViewCache.setOnClickListener {
            val galleryDialog = PrintCacheGalleryDialogFragment.newInstance()
            galleryDialog.show(parentFragmentManager, "PrintCacheGallery")
        }

        val btnCheckUpdates = view.findViewById<Button>(R.id.btnCheckUpdates)
        btnCheckUpdates.setOnClickListener {
            var currentVer = "5.0.0"
            try {
                currentVer = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "5.0.0"
            } catch (_: Exception) {}

            val updateManager = AppUpdateManager(requireContext(), currentVer, { msg ->
                activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
            }, { latestTag, releaseNotes, apkUrl ->
                activity?.runOnUiThread { showUpdateAvailableDialog(latestTag, releaseNotes, apkUrl) }
            })
            updateManager.checkForUpdates(false)
        }

        return view
    }

    private fun showUpdateAvailableDialog(latestTag: String, releaseNotes: String, apkUrl: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Cuppa update available (v$latestTag)")
            .setMessage(releaseNotes)
            .setPositiveButton(R.string.update_now) { _, _ ->
                var currentVer = "5.0.0"
                try {
                    currentVer = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "5.0.0"
                } catch (_: Exception) {}
                val updateManager = AppUpdateManager(requireContext(), currentVer, {}, { _, _, _ -> })
                updateManager.downloadAndInstallApk(apkUrl) { msg ->
                    activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(R.string.ignore, null)
            .show()
    }
}
