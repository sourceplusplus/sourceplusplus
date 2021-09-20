package spp.probe.services.common.model

import junit.framework.TestCase
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HitThrottleTest {

    @Test
    @Throws(Exception::class)
    fun oneASecond() {
        val hitThrottle = HitThrottle(1, HitThrottleStep.SECOND)
        val scheduler = Executors.newScheduledThreadPool(1)
        val beeperHandle = scheduler.scheduleAtFixedRate({ hitThrottle.isRateLimited }, 0, 100, TimeUnit.MILLISECONDS)
        scheduler.schedule({ beeperHandle.cancel(true) }, 5, TimeUnit.SECONDS).get()
        scheduler.shutdown()
        TestCase.assertEquals(5f, hitThrottle.totalHitCount.toFloat(), 1f)
        TestCase.assertEquals(45f, hitThrottle.totalLimitedCount.toFloat(), 1f)
    }

    @Test
    @Throws(Exception::class)
    fun twiceASecond() {
        val hitThrottle = HitThrottle(2, HitThrottleStep.SECOND)
        val scheduler = Executors.newScheduledThreadPool(1)
        val beeperHandle = scheduler.scheduleAtFixedRate({ hitThrottle.isRateLimited }, 0, 225, TimeUnit.MILLISECONDS)
        scheduler.schedule({ beeperHandle.cancel(true) }, 5, TimeUnit.SECONDS).get()
        scheduler.shutdown()
        TestCase.assertEquals(10f, hitThrottle.totalHitCount.toFloat(), 1f)
        TestCase.assertEquals(12f, hitThrottle.totalLimitedCount.toFloat(), 1f)
    }

    @Test
    @Throws(Exception::class)
    fun fourTimesASecond() {
        val hitThrottle = HitThrottle(4, HitThrottleStep.SECOND)
        val scheduler = Executors.newScheduledThreadPool(1)
        val beeperHandle = scheduler.scheduleAtFixedRate({ hitThrottle.isRateLimited }, 0, 225, TimeUnit.MILLISECONDS)
        scheduler.schedule({ beeperHandle.cancel(true) }, 5, TimeUnit.SECONDS).get()
        scheduler.shutdown()
        TestCase.assertEquals(20f, hitThrottle.totalHitCount.toFloat(), 1f)
        TestCase.assertEquals(3f, hitThrottle.totalLimitedCount.toFloat(), 1f)
    }
}