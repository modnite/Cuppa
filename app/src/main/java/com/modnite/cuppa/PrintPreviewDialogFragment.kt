package com.modnite.cuppa

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton

class PrintPreviewDialogFragment : DialogFragment() {

    interface OnConfirmListener {
        fun onConfirm()
    }

    private var onConfirmListener: OnConfirmListener? = null
    private var originalBitmap: Bitmap? = null

    companion object {
        fun newInstance(bitmap: Bitmap, listener: OnConfirmListener): PrintPreviewDialogFragment {
            return PrintPreviewDialogFragment().apply {
                originalBitmap = bitmap
                onConfirmListener = listener
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_print_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val dialogRoot = view.findViewById<View>(R.id.dialogRoot)
        if (dialogRoot != null) {
            ViewCompat.setOnApplyWindowInsetsListener(dialogRoot) { v, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val density = resources.displayMetrics.density
                val basePaddingPx = (24 * density).toInt()

                v.setPadding(
                    insets.left + basePaddingPx,
                    insets.top + basePaddingPx,
                    insets.right + basePaddingPx,
                    insets.bottom + basePaddingPx
                )
                WindowInsetsCompat.CONSUMED
            }
        }

        val ivPreview = view.findViewById<ImageView>(R.id.ivPreview)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnPrintConfirm)

        ivPreview.setImageBitmap(originalBitmap)

        btnCancel.setOnClickListener { dismiss() }

        btnConfirm.setOnClickListener {
            onConfirmListener?.onConfirm()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }
}
