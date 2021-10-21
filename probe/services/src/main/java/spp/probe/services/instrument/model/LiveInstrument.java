package spp.probe.services.instrument.model;

import org.springframework.expression.Expression;
import spp.probe.services.common.ModelSerializer;
import spp.probe.services.common.model.HitThrottle;
import spp.probe.services.common.model.Location;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

public class LiveInstrument implements Serializable {

    private final String id;
    private final Location location;
    private final transient Expression expression;
    private final String condition;
    private final int hitLimit;
    private final Long expiresAt;
    private transient boolean removal;
    private boolean applyImmediately;
    private transient boolean live;
    private final HitThrottle throttle;

    public LiveInstrument(String id, Location location, Expression expression, int hitLimit,
                          HitThrottle throttle, Long expiresAt) {
        this.id = id;
        this.location = location;
        this.expression = expression;
        if (expression != null) {
            this.condition = expression.getExpressionString();
        } else {
            this.condition = null;
        }
        this.hitLimit = hitLimit;
        this.throttle = throttle;
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public Location getLocation() {
        return location;
    }

    public Expression getExpression() {
        return expression;
    }

    @SuppressWarnings("unused")
    public String getCondition() {
        return condition;
    }

    public HitThrottle getThrottle() {
        return throttle;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setRemoval(boolean removal) {
        this.removal = removal;
    }

    public boolean isRemoval() {
        return removal;
    }

    public void setApplyImmediately(boolean applyImmediately) {
        this.applyImmediately = applyImmediately;
    }

    public boolean isApplyImmediately() {
        return applyImmediately;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public boolean isLive() {
        return live;
    }

    public boolean isFinished() {
        if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
            return true;
        } else {
            return hitLimit != -1 && throttle.getTotalHitCount() >= hitLimit;
        }
    }

    public String toJson() {
        return ModelSerializer.INSTANCE.toJson(this);
    }
}
