package io.github.olaaa.cachinglab.scenario;

import java.math.BigDecimal;

import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/*
 * Сценарий 3. Тот же Cache-Aside, но декларативно: @Cacheable на методе, который
 * возвращает Mono. Spring кэширует не Mono, а значение, которое он отдал.
 */
@Service
public class AnnotatedProductService {

    public static final String CACHE_NAME = "products";

    private final ProductSource source;

    public AnnotatedProductService(ProductSource source) {
        this.source = source;
    }

    @Cacheable(CACHE_NAME)
    public Mono<Product> findById(long id) {
        return source.load(id);
    }

    /*
     * То же, но с sync = true: одновременные промахи по одному ключу загружают значение один раз.
     * Spring вызывает Cache.retrieve(key, valueLoader), CaffeineCache превращает его в
     * AsyncCache.get(key, loader), и все ждут одну future.
     *
     * Ловушка: при sync = true кэш опрашивается и загрузка запускается в момент вызова метода,
     * а не при подписке на Mono. В CacheAspectSupport стоит Mono.fromFuture(doRetrieve(...))
     * без лямбды, а загрузчик подписывается на Mono метода через toFuture() сразу.
     */
    @Cacheable(cacheNames = CACHE_NAME, sync = true)
    public Mono<Product> findByIdSync(long id) {
        return source.load(id);
    }

    /*
     * Ловушка: вызов findById через this идёт мимо прокси Spring, поэтому кэш не работает,
     * и каждый вызов доходит до источника. Правильно — вызывать кэшируемый метод из другого
     * бина, как это делает ProductPriceQueries.
     */
    public Mono<BigDecimal> findPrice(long id) {
        return findById(id).map(Product::price);
    }
}
