package spp.processor.live.impl.view.util

import java.util.concurrent.ConcurrentHashMap

class EntitySubscribersCache : ConcurrentHashMap<String, Set<ViewSubscriber>>()
