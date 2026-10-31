package io.github.olaaa.cachinglab.scenario;

import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/*
 * Сценарий 9. @Cacheable(sync = true): одновременные промахи по одному ключу загружают значение
 * один раз. Spring вызывает Cache.retrieve(key, valueLoader), CaffeineCache превращает его в
 * AsyncCache.get(key, loader), и все ждут одну future.
 *
 * Ловушка: при sync = true кэш опрашивается и загрузка запускается в момент вызова метода,
 * а не при подписке на Mono. В CacheAspectSupport стоит Mono.fromFuture(doRetrieve(...))
 * без лямбды, а загрузчик подписывается на Mono метода через toFuture() сразу.
 */
@Service
public class SyncCacheableProductService {

    public static final String CACHE_NAME = "productsSync";

    private final ProductSource source;

    public SyncCacheableProductService(ProductSource source) {
        this.source = source;
    }

    @Cacheable(cacheNames = CACHE_NAME, sync = true)
    public Mono<Product> findById(long id) {
        return source.load(id);
    }
}
