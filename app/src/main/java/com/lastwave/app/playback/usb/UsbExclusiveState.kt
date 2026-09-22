package com.lastwave.app.playback.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import android.hardware.usb.UsbConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Availability/activity state for USB exclusive output.
 *
 * [isAvailable] tracks whether a USB audio peripheral is currently attached.
 * [isActive] is true only when the exclusive toggle is enabled and a device
 * is attached. Both default to `false`.
 */
@Singleton
class UsbExclusiveState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    val isActive: StateFlow<Boolean> = combine(
        UsbExclusivePrefs.enabledFlow(context),
        _isAvailable,
    ) { enabled, available -> enabled && available }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scope.launch { refresh() }
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        scope.launch { refresh() }
    }

    /** Re-checks USB bus presence; safe to call from anywhere. */
    fun refresh() {
        val attached = runCatching {
            val manager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            manager.deviceList.values.any { dev ->
                (0 until dev.interfaceCount).any {
                    dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
                }
            }
        }.getOrDefault(false)
        _isAvailable.value = attached
    }
}
