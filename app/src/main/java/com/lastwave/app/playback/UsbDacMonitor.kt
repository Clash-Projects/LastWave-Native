package com.lastwave.app.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * USB DAC presence, capability and permission monitor.
 *
 * Two independent sources are merged: the platform mixer
 * ([AudioManager.getDevices], the routable [AudioDeviceInfo]) and the USB
 * bus itself ([UsbManager], needed for the permission grant). A DAC counts
 * as connected when the mixer exposes a USB output; the permission flag
 * tracks whether direct USB access was granted by the user.
 */
@Singleton
class UsbDacMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applicationScope: CoroutineScope,
) {
    data class State(
        val dac: UsbDacInfo? = null,
        /** LastWave asked AudioTrack to prefer this DAC. */
        val routeRequested: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                ACTION_USB_PERMISSION -> applicationScope.launch { refresh() }
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            applicationScope.launch { refresh() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            applicationScope.launch { refresh() }
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
                    addAction(ACTION_USB_PERMISSION)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        runCatching {
            audioManager?.registerAudioDeviceCallback(
                deviceCallback,
                Handler(Looper.getMainLooper()),
            )
        }
        applicationScope.launch { refresh() }
    }

    fun setRouteRequested(requested: Boolean) {
        _state.update { if (it.routeRequested == requested) it else it.copy(routeRequested = requested) }
    }

    /** Re-reads mixer devices + USB bus; safe to call from anywhere. */
    fun refresh() {
        val mixer = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.isUsbOutput() }
        }.getOrNull()
        val bus = runCatching {
            usbManager?.deviceList?.values?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val peripheral = bus.firstOrNull { it.isAudioPeripheral() }
        val granted = peripheral?.let { runCatching { usbManager?.hasPermission(it) }.getOrDefault(false) } == true

        val mixerDac = mixer?.let { info ->
            UsbDacInfo(
                name = info.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: peripheral?.productName?.takeIf { it.isNotBlank() }
                    ?: "USB DAC",
                vendorId = peripheral?.vendorId ?: -1,
                productId = peripheral?.productId ?: -1,
                sampleRatesHz = runCatching { info.sampleRates?.toList() }.getOrNull().orEmpty(),
                channelCounts = runCatching { info.channelCounts?.toList() }.getOrNull().orEmpty(),
                usbPermissionGranted = granted,
                hasUsbPeripheral = peripheral != null,
                deviceId = info.id,
            )
        }
        val dac = mixerDac ?: peripheral?.let { usb ->
            UsbDacInfo(
                name = usb.productName?.takeIf { it.isNotBlank() } ?: "USB DAC",
                vendorId = usb.vendorId,
                productId = usb.productId,
                sampleRatesHz = emptyList(),
                channelCounts = emptyList(),
                usbPermissionGranted = granted,
                hasUsbPeripheral = true,
                deviceId = -1,
            )
        }
        _state.update { current ->
            val stillThere = when {
                dac == null -> false
                dac.deviceId > 0 && current.dac?.deviceId == dac.deviceId -> true
                dac.hasUsbPeripheral && current.dac?.vendorId == dac.vendorId &&
                    current.dac?.productId == dac.productId -> true
                else -> false
            }
            current.copy(
                dac = dac,
                routeRequested = if (dac == null) false else current.routeRequested && stillThere,
            )
        }
    }

    /**
     * Asks the user for direct USB access to the audio peripheral. Needs no
     * Activity — the grant returns to our private broadcast.
     */
    fun requestPermission() {
        val manager = usbManager ?: return
        val device = runCatching {
            manager.deviceList.values.firstOrNull { it.isAudioPeripheral() }
        }.getOrNull() ?: return
        if (runCatching { manager.hasPermission(device) }.getOrDefault(false)) {
            applicationScope.launch { refresh() }
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, USB_PERMISSION_REQUEST, intent, flags)
        runCatching { manager.requestPermission(device, pending) }
    }

    private fun AudioDeviceInfo.isUsbOutput(): Boolean = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun UsbDevice.isAudioPeripheral(): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (index in 0 until interfaceCount) {
            if (runCatching { getInterface(index).interfaceClass }.getOrNull() == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.lastwave.app.playback.USB_PERMISSION"
        const val USB_PERMISSION_REQUEST = 4107
    }
}
