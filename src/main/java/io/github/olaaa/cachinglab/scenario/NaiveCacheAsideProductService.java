package io.github.olaaa.cachinglab.scenario;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/*
 * Сценарий 6. Наивный Cache-Aside, каким его пишут по учебнику:
 * «посмотри в кэш, если нет — сходи в источник и положи».
 * Между «нет в кэше» и «положил» проходит всё время загрузки, и все запросы,
 * пришедшие за это время, тоже идут в источник.
 */
@Service
public class NaiveCacheAsideProductService implements LabCache {

    private final ProductSource source;
    private final Cache<Long, Product> cache;

    public NaiveCacheAsideProductService(ProductSource source, Ticker cacheTicker) {
        this.source = source;
        this.cache = Caffeine.newBuilder()
                .ticker(cacheTicker)
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    public Mono<Product> findById(long id) {
        return Mono.defer(() -> {
            Product cachedProduct = cache.getIfPresent(id);
            if (cachedProduct != null) {
                return Mono.just(cachedProduct);
            }
            return source.load(id).doOnNext(product -> cache.put(id, product));
        });
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }
}
