package io.github.olaaa.cachinglab.web;

import java.util.List;

import io.github.olaaa.cachinglab.catalog.ProductSource;
import io.github.olaaa.cachinglab.scenario.CacheAsideProductService;
import io.github.olaaa.cachinglab.scenario.LabCache;
import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/* Служебные ручки для ручных экспериментов: сколько раз дошли до источника, сброс кэшей. */
@RestController
@RequestMapping("/lab")
public class LabController {

    public record LabStats(int sourceCalls, long cacheAsideHits, long cacheAsideMisses, double cacheAsideHitRate) {
    }

    private final ProductSource source;
    private final CacheAsideProductService cacheAsideProductService;
    private final List<LabCache> labCaches;
    private final CacheManager cacheManager;

    public LabController(ProductSource source,
                         CacheAsideProductService cacheAsideProductService,
                         List<LabCache> labCaches,
                         CacheManager cacheManager) {
        this.source = source;
        this.cacheAsideProductService = cacheAsideProductService;
        this.labCaches = labCaches;
        this.cacheManager = cacheManager;
    }

    @GetMapping("/stats")
    public LabStats stats() {
        var stats = cacheAsideProductService.stats();
        return new LabStats(source.callCount(), stats.hitCount(), stats.missCount(), stats.hitRate());
    }

    @PostMapping("/source/fail")
    public void failSource() {
        source.setFailing(true);
    }

    @PostMapping("/source/recover")
    public void recoverSource() {
        source.setFailing(false);
    }

    @PostMapping("/reset")
    public void reset() {
        source.reset();
        labCaches.forEach(LabCache::invalidateAll);
        for (String cacheName : cacheManager.getCacheNames()) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
