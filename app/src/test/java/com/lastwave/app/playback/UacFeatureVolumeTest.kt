package com.lastwave.app.playback

import android.hardware.usb.UsbDeviceConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class UacFeatureVolumeTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    private fun shortToLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )

    private fun rangePayload(min: Int, max: Int, res: Int): ByteArray = byteArrayOf(
        0x01, 0x00,
        (min and 0xFF).toByte(), ((min shr 8) and 0xFF).toByte(),
        (max and 0xFF).toByte(), ((max shr 8) and 0xFF).toByte(),
        (res and 0xFF).toByte(), ((res shr 8) and 0xFF).toByte(),
    )

    // Captured 288-byte descriptors from CX31993 384Khz HIFI AUDIO (vid=0x6cb pid=0x1595):
    // FU 0x02 has bmaControls = [ch0=0x03 (Mute only), ch1=0x0c (Vol only), ch2=0x0c (Vol only)].
    private val cx31993Descriptors = hexToBytes(
        "12010102ef020140cb06951502000102000109020e01030104a032080b000201" +
            "00200009040000000101200009240100020440000008240a0903070000112402" +
            "01010100090203000000000000001224060201030000000c0000000c00000000" +
            "0c24030302030002090000000904010000010220000904010101010220001024" +
            "01010001010000000203000000000624020102100705010dc000010825010000" +
            "0202000904010201010220001024010100010100000002030000000006240201" +
            "03180705010d2001010825010000020200090401030101022000102401010001" +
            "010000000203000000000624020104200705010d800101082501000002020008" +
            "0b020103000000090402000103000000092111010001223f0007058303400007",
    )

    @Test
    fun attachUnmutesMasterCh0OnSplitMuteVolumeFeatureUnit() {
        val volumes = mutableMapOf(1 to 0, 2 to 0)
        val mutes = mutableMapOf(0 to 1)
        val conn = mockk<UsbDeviceConnection>()
        every { conn.rawDescriptors } returns cx31993Descriptors
        every { conn.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers {
            val reqType = arg<Int>(0)
            val req = arg<Int>(1)
            val wValue = arg<Int>(2)
            val wIndex = arg<Int>(3)
            val buf = arg<ByteArray>(4)
            val unit = (wIndex shr 8) and 0xFF
            val cs = (wValue shr 8) and 0xFF
            val ch = wValue and 0xFF
            if (unit != 0x02) return@answers -1
            when {
                reqType == 0xA1 && req == 0x02 && cs == 0x02 && ch in 1..2 -> {
                    rangePayload(-12800, 0, 256).copyInto(buf)
                    8
                }
                reqType == 0xA1 && (req == 0x01 || req == 0x81) && cs == 0x02 && ch in 1..2 -> {
                    shortToLe(volumes.getValue(ch)).copyInto(buf)
                    2
                }
                reqType == 0x21 && req == 0x01 && cs == 0x02 && ch in 1..2 -> {
                    volumes[ch] = ((buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)).toShort().toInt()
                    2
                }
                reqType == 0x21 && req == 0x01 && cs == 0x01 && ch == 0 -> {
                    mutes[0] = buf[0].toInt() and 0xFF
                    1
                }
                else -> -1
            }
        }

        val fu = UacFeatureVolume(conn, controlInterfaceId = 0)
        assertThat(fu.attach()).isTrue()
        assertThat(mutes[0]).isEqualTo(0)

        assertThat(fu.setNormalized(0f)).isTrue()
        assertThat(mutes[0]).isEqualTo(1)

        assertThat(fu.setNormalized(0.5f)).isTrue()
        assertThat(mutes[0]).isEqualTo(0)
    }

    @Test
    fun parkUnityAndUnmuteClearsCh0MuteAndParksCh12AtZeroDbWhenAttachFails() {
        val volumes = mutableMapOf(1 to -12800, 2 to -12800)
        val mutes = mutableMapOf(0 to 1)
        val conn = mockk<UsbDeviceConnection>()
        every { conn.rawDescriptors } returns cx31993Descriptors
        every { conn.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers {
            val reqType = arg<Int>(0)
            val req = arg<Int>(1)
            val wValue = arg<Int>(2)
            val wIndex = arg<Int>(3)
            val buf = arg<ByteArray>(4)
            val unit = (wIndex shr 8) and 0xFF
            val cs = (wValue shr 8) and 0xFF
            val ch = wValue and 0xFF
            if (unit != 0x02) return@answers -1
            when {
                reqType == 0xA1 && req == 0x02 && cs == 0x02 && ch in 1..2 -> {
                    rangePayload(-12800, 0, 256).copyInto(buf)
                    8
                }
                // Simulate stuck/slow readback so attach() fails closed (hardwareVolume=false)
                reqType == 0xA1 && (req == 0x01 || req == 0x81) && cs == 0x02 && ch in 1..2 -> {
                    shortToLe(-12800).copyInto(buf)
                    2
                }
                reqType == 0x21 && req == 0x01 && cs == 0x02 && ch in 1..2 -> {
                    volumes[ch] = ((buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)).toShort().toInt()
                    2
                }
                reqType == 0x21 && req == 0x01 && cs == 0x01 && ch == 0 -> {
                    mutes[0] = buf[0].toInt() and 0xFF
                    1
                }
                else -> -1
            }
        }

        val fu = UacFeatureVolume(conn, controlInterfaceId = 0)
        assertThat(fu.attach()).isFalse()
        assertThat(mutes[0]).isEqualTo(1)

        fu.parkUnityAndUnmute()
        assertThat(mutes[0]).isEqualTo(0)
        assertThat(volumes[1]).isEqualTo(0)
        assertThat(volumes[2]).isEqualTo(0)
    }

    @Test
    fun regularMasterChannelDacRemainsUnchangedAndSkipsMicFeatureUnitsOnPark() {
        // Synthetic descriptor with capture FU 0x05 (mic terminal 0x0201) + playback FU 0x02 (headphones 0x0302)
        val dualFuDescriptors = byteArrayOf(
            0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x20, 0x00, // AC interface 0
            0x0C, 0x24, 0x06, 0x05, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Capture FU 0x05
            0x09, 0x24, 0x03, 0x06, 0x01, 0x01, 0x00, 0x05, 0x00, // USB Streaming OT (capture) -> src 0x05
            0x0C, 0x24, 0x06, 0x02, 0x01, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Playback FU 0x02
            0x0C, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x02, 0x09, 0x00, 0x00, 0x00, // Headphones OT -> src 0x02
        )
        val touchedUnits = mutableSetOf<Int>()
        val volumes = mutableMapOf(0 to 0)
        val mutes = mutableMapOf(0 to 1)
        val conn = mockk<UsbDeviceConnection>()
        every { conn.rawDescriptors } returns dualFuDescriptors
        every { conn.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers {
            val reqType = arg<Int>(0)
            val req = arg<Int>(1)
            val wValue = arg<Int>(2)
            val wIndex = arg<Int>(3)
            val buf = arg<ByteArray>(4)
            val unit = (wIndex shr 8) and 0xFF
            val cs = (wValue shr 8) and 0xFF
            val ch = wValue and 0xFF
            touchedUnits += unit
            if (unit != 0x02) return@answers -1
            when {
                reqType == 0xA1 && req == 0x02 && cs == 0x02 && ch == 0 -> {
                    rangePayload(-15872, 0, 256).copyInto(buf)
                    8
                }
                reqType == 0xA1 && (req == 0x01 || req == 0x81) && cs == 0x02 && ch == 0 -> {
                    shortToLe(volumes.getValue(0)).copyInto(buf)
                    2
                }
                reqType == 0x21 && req == 0x01 && cs == 0x02 && ch == 0 -> {
                    volumes[0] = ((buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)).toShort().toInt()
                    2
                }
                reqType == 0x21 && req == 0x01 && cs == 0x01 && ch == 0 -> {
                    mutes[0] = buf[0].toInt() and 0xFF
                    1
                }
                else -> -1
            }
        }

        val fu = UacFeatureVolume(conn, controlInterfaceId = 0)
        assertThat(fu.attach()).isTrue()
        assertThat(mutes[0]).isEqualTo(0)

        touchedUnits.clear()
        fu.parkUnityAndUnmute()
        assertThat(touchedUnits).containsExactly(0x02)
        assertThat(mutes[0]).isEqualTo(0)
        assertThat(volumes[0]).isEqualTo(0)
    }
}
