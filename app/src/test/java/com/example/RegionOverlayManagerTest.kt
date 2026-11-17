package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.region.PixelRegion
import com.example.region.RegionOverlayManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegionOverlayManagerTest {

  @Test
  fun `show and hide from background thread do not throw`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = RegionOverlayManager(context)
    val region = PixelRegion(100, 200, 700, 1200, 1080, 2400)
    val errorRef = AtomicReference<Throwable?>(null)
    val latch = CountDownLatch(1)

    Thread {
        try {
          manager.show(region, enabled = true)
          manager.setPaused(true)
          manager.hide()
        } catch (t: Throwable) {
          errorRef.set(t)
        } finally {
          latch.countDown()
        }
      }
      .start()

    latch.await(3, TimeUnit.SECONDS)
    assertNull(errorRef.get())
  }
}
