package com.modnite.cuppa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogTabFragment : Fragment() {

    private lateinit var tvLog: TextView
    private lateinit var scrollViewLog: ScrollView

    private val saveLogLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val logText = tvLog.text.toString()
                        outputStream.write(logText.toByteArray(Charsets.UTF_8))
                        Toast.makeText(requireContext(), "Log saved successfully.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error saving log: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_log_tab, container, false)
        tvLog = view.findViewById(R.id.tvLog)
        scrollViewLog = view.findViewById(R.id.scrollViewLog)

        val btnSaveLog = view.findViewById<MaterialButton>(R.id.btnSaveLog)
        btnSaveLog.setOnClickListener {
            val logContent = tvLog.text.toString()
            if (logContent.isBlank()) {
                Toast.makeText(requireContext(), "Activity log is empty. Nothing to save.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "cuppa_server_log_$timestamp.txt")
            }
            saveLogLauncher.launch(intent)
        }

        return view
    }

    fun log(text: String) {
        activity?.runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLog.append("[$timestamp] $text\n")
            scrollViewLog.post { scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    fun getLogText(): String {
        return tvLog.text.toString()
    }
}
