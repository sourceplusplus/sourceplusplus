package spp.processor.live.impl.view.util

import java.util.concurrent.ConcurrentHashMap

class MetricTypeSubscriptionCache : ConcurrentHashMap<String, EntitySubscribersCache>()
