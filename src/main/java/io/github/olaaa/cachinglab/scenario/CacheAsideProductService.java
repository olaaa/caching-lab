package io.github.olaaa.cachinglab.scenario;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/*
 * Сценарии 2, 4, 5 и 6. Cache-Aside вручную на AsyncCache Caffeine.
 *
 * В кэше лежит не товар, а CompletableFuture с товаром. Пока первая загрузка идёт,
 * остальные запросы того же ключа получают ту же future и ждут её — до источника
 * доходит один запрос (защита от лавины промахов, сценарий 6).
 */
@Service
public class CacheAsideProductService implements LabCache {

    public static final String CACHE_NAME = "products-cache-aside";
    public static final Duration TIME_TO_LIVE = Duration.ofMinutes(5);

    private final ProductSource source;
    private final AsyncCache<Long, Product> cache;

    public CacheAsideProductService(ProductSource source, Ticker cacheTicker, MeterRegistry meterRegistry) {
        this.source = source;
        this.cache = Caffeine.newBuilder()
                .ticker(cacheTicker)
                .maximumSize(10_000) // обязательно
                .expireAfterWrite(TIME_TO_LIVE) // ttl обязательно
                .recordStats()
                .buildAsync();
        /* Метрики cache.gets, cache.puts, cache.evictions видны в /actuator/metrics. */
        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME);
    }

    public Mono<Product> findById(long id) {
        /*
         * Supplier — чтобы обращение к кэшу случилось при подписке, а не при сборке Mono.
         * suppressCancel = true — отмена одного подписчика не должна отменять общую future,
         * которую ждут другие запросы.
         *
         * Загрузчик подписывается через toFuture() без Reactor Context вызывающего:
         * если чтение случится внутри TransactionalOperator, загрузка пойдёт по другому
         * соединению и увидит только закоммиченные данные (сценарий 5).
         *
         * Пустой Mono превращается в future с null — Caffeine такую запись не хранит,
         * поэтому отсутствующий товар каждый раз идёт в источник (сценарий 8).
         */
        return Mono.fromFuture(() -> cache.get(id,
 // загрузить и положить в кэш
                        (key, executor) -> source.load(key)
    // AsyncCache кладёт future в кэш сразу, до того как загрузка закончится, сц. 6
                                .toFuture()
                ),
                true);
    }

    public void evict(long id) {
        cache.synchronous().invalidate(id);
    }

    /*
     * Возраст записи по часам кэша. Пока загрузка не завершилась, возраста нет:
     * время записи AsyncCache выставляет в момент завершения future, а не в момент запроса.
     */
    public Optional<Duration> ageOf(long id) {
        return cache.synchronous().policy().expireAfterWrite()
                .flatMap(expiration -> expiration.ageOf(id));
    }

    /* Есть ли в кэше запись для ключа, в том числе ещё не завершённая future. */
    public boolean contains(long id) {
        return cache.asMap().containsKey(id);
    }

    public CacheStats stats() {
        return cache.synchronous().stats();
    }

    @Override
    public void invalidateAll() {
        cache.synchronous().invalidateAll();
    }
}
