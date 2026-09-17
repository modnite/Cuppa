package com.cuppa.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.cuppa.app.data.CupsRepository
import com.cuppa.app.util.CuppaLog
import com.cuppa.app.util.UsbPermissionHelper
import com.cuppa.app.util.safeDescription

/**
 * UsbPermissionReceiver — Receives system broadcasts for USB device permission prompts and attach/detach events.
 */
class UsbPermissionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbPermissionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        when (action) {
            UsbPermissionHelper.ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val desc = device?.safeDescription() ?: "Unknown USB Device"
                CuppaLog.i(TAG, "USB Permission Dialog Result: device=$desc, granted=$granted")
                UsbPermissionHelper.notifyPermissionChanged()

                if (granted) {
                    val repo = CupsRepository.getInstance(context)
                    repo.loadPrinters(context)
                    repo.syncAllPrintersToNative()
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val desc = device?.safeDescription() ?: "Unknown USB Device"
                CuppaLog.i(TAG, "USB Device Attached: $desc")
                if (device != null) {
                    UsbPermissionHelper.requestUsbPermission(context, device)
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val desc = device?.safeDescription() ?: "Unknown USB Device"
                CuppaLog.i(TAG, "USB Device Detached: $desc")
                UsbPermissionHelper.notifyPermissionChanged()
            }
        }
    }
}
