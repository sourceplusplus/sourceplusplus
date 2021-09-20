package spp.probe.services.common.model;

import java.io.Serializable;

public class HitThrottle implements Serializable {

    private final int limit;
    private final HitThrottleStep step;
    private transient long lastReset = -1;
    private transient int hitCount = 0;
    private transient int totalHitCount = 0;
    private transient int totalLimitedCount = 0;

    public HitThrottle(int limit, HitThrottleStep step) {
        this.limit = limit;
        this.step = step;
    }

    public boolean isRateLimited() {
        if (hitCount++ < limit) {
            totalHitCount++;
            return false;
        }

        if (System.currentTimeMillis() - lastReset > step.toMillis(1)) {
            hitCount = 1;
            totalHitCount++;
            lastReset = System.currentTimeMillis();
            return false;
        } else {
            totalLimitedCount++;
            return true;
        }
    }

    public int getTotalHitCount() {
        return totalHitCount;
    }

    public int getTotalLimitedCount() {
        return totalLimitedCount;
    }
}
