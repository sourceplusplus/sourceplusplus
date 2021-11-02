package spp.platform.util

import ch.qos.logback.core.rolling.RollingFileAppender
import org.graalvm.nativeimage.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RollingFileAppender with lazy initialization on GraalVM native image.
 *
 * Logback's rolling file appended does not work with Graal native image out of
 * the box. Reflection config has been added on `reflect-config.json` as well
 * to make this work.
 *
 * See:
 *   - https://github.com/oracle/graal/issues/1323
 *   - https://gist.github.com/begrossi/d807280f54d3378d407e9c9a95e5d905
 *
 * @tparam T Event object type parameter.
 */
class LazyInitRollingFileAppender<T> : RollingFileAppender<T>() {

    private val lazyStarted = AtomicBoolean(false)

    override fun start() {
        if (!ImageInfo.inImageBuildtimeCode()) {
            super.start()

            lazyStarted.set(true)
        }
    }

    override fun doAppend(eventObject: T) {
        if (!ImageInfo.inImageBuildtimeCode()) {
            if (!lazyStarted.get()) maybeStart()
            super.doAppend(eventObject)
        }
    }

    /**
     * Synchronised method to avoid double start from `doAppender()`.
     */
    private fun maybeStart() {
        lock.lock()
        try {
            if (!lazyStarted.get()) this.start()
        } finally {
            lock.unlock()
        }
    }
}
