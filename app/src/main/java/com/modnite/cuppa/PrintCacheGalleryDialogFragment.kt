package com.modnite.cuppa

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import java.io.File

class PrintCacheGalleryDialogFragment : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(): PrintCacheGalleryDialogFragment {
            return PrintCacheGalleryDialogFragment()
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
        return inflater.inflate(R.layout.dialog_print_cache_gallery, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvCacheItems = view.findViewById<RecyclerView>(R.id.rvCacheItems)
        val tvEmptyCachePlaceholder = view.findViewById<TextView>(R.id.tvEmptyCachePlaceholder)
        val btnToggleCacheSort = view.findViewById<ImageButton>(R.id.btnToggleCacheSort)
        val btnExportAllZip = view.findViewById<MaterialButton>(R.id.btnExportAllZip)
        val btnClearCacheGallery = view.findViewById<MaterialButton>(R.id.btnClearCacheGallery)
        val btnCloseCacheGallery = view.findViewById<MaterialButton>(R.id.btnCloseCacheGallery)

        rvCacheItems.layoutManager = LinearLayoutManager(requireContext())

        var isNewestFirst = true

        fun loadCacheFiles(): List<File> {
            val cacheDir = requireContext().getExternalFilesDir(PrintHistoryCacheManager.CACHE_DIR_NAME)
                ?: File(requireContext().filesDir, PrintHistoryCacheManager.CACHE_DIR_NAME)
            val files = cacheDir.listFiles { file -> file.extension.lowercase() == "jpg" }?.toList() ?: emptyList()
            return if (isNewestFirst) files.sortedByDescending { it.lastModified() } else files.sortedBy { it.lastModified() }
        }

        fun refreshAdapter() {
            val files = loadCacheFiles()
            if (files.isEmpty()) {
                rvCacheItems.visibility = View.GONE
                tvEmptyCachePlaceholder?.visibility = View.VISIBLE
            } else {
                rvCacheItems.visibility = View.VISIBLE
                tvEmptyCachePlaceholder?.visibility = View.GONE
                rvCacheItems.adapter = CacheGalleryAdapter(
                    files,
                    onExportSingle = { file ->
                        try {
                            val contentUri: Uri = FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.fileprovider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, contentUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(shareIntent, "Export JPEG Screenshot"))
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        btnToggleCacheSort?.setOnClickListener {
            isNewestFirst = !isNewestFirst
            if (isNewestFirst) {
                btnToggleCacheSort.setImageResource(R.drawable.ic_arrow_downward)
                btnToggleCacheSort.contentDescription = getString(R.string.sort_newest_first)
            } else {
                btnToggleCacheSort.setImageResource(R.drawable.ic_arrow_upward)
                btnToggleCacheSort.contentDescription = getString(R.string.sort_oldest_first)
            }
            refreshAdapter()
        }

        btnExportAllZip?.setOnClickListener {
            try {
                val zipFile = PrintHistoryCacheManager.exportCacheZip(requireContext())
                if (zipFile != null && zipFile.exists()) {
                    val contentUri: Uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        zipFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Print Cache ZIP"))
                } else {
                    Toast.makeText(requireContext(), R.string.no_cached_screenshots, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export ZIP failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearCacheGallery?.setOnClickListener {
            val cleared = PrintHistoryCacheManager.clearCache(requireContext())
            if (cleared) {
                Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                refreshAdapter()
            }
        }

        btnCloseCacheGallery.setOnClickListener { dismiss() }

        refreshAdapter()
    }

    private class CacheGalleryAdapter(
        private val files: List<File>,
        private val onExportSingle: (File) -> Unit
    ) : RecyclerView.Adapter<CacheGalleryAdapter.CacheViewHolder>() {

        class CacheViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivCacheThumbnail: ImageView = view.findViewById(R.id.ivCacheThumbnail)
            val tvCacheTitle: TextView = view.findViewById(R.id.tvCacheTitle)
            val tvCacheDetails: TextView = view.findViewById(R.id.tvCacheDetails)
            val tvCacheTimestamp: TextView = view.findViewById(R.id.tvCacheTimestamp)
            val btnExportSingleJpeg: MaterialButton = view.findViewById(R.id.btnExportSingleJpeg)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CacheViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_print_cache, parent, false)
            return CacheViewHolder(view)
        }

        override fun onBindViewHolder(holder: CacheViewHolder, position: Int) {
            val file = files[position]
            val parts = file.nameWithoutExtension.split("_")

            // Format: job_<id>_<origin>_<osFamily>_<timestamp>
            val jobId = parts.getOrNull(1) ?: "0"
            val origin = parts.getOrNull(2) ?: "unknown"
            val osFamily = parts.getOrNull(3) ?: "unknown"

            holder.tvCacheTitle.text = "Job #$jobId [$osFamily]"
            holder.tvCacheDetails.text = "Origin: $origin | OS: $osFamily"

            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    holder.ivCacheThumbnail.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {}

            holder.tvCacheTimestamp.text = file.name.substringAfterLast("_").substringBefore(".jpg")

            holder.btnExportSingleJpeg.setOnClickListener {
                onExportSingle(file)
            }
        }

        override fun getItemCount(): Int = files.size
    }
}
