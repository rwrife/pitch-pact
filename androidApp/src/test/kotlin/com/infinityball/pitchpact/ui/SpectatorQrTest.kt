package com.infinityball.pitchpact.ui

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpectatorQrTest {
    @Test fun qrDecodesToSpectatorLinkOnly() {
        val link = "https://example.test/spectate/ABC123"
        val bitmap = spectatorQr(link)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        assertEquals(link, MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
    }
}
