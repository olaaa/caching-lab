package io.github.olaaa.cachinglab.scenario;

import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/*
 * Сценарий 7. Refresh-Ahead: refreshAfterWrite плюс expireAfterWrite.
 *
 * После REFRESH_AFTER первое же чтение получает старое значение сразу, а Caffeine
 * в фоне перезагружает запись. Если источник в этот момент упал, остаётся старое
 * значение — кэш продолжает отвечать. Через EXPIRE_AFTER запись удаляется, и
 * ошибка источника доходит до клиента: так ограничен возраст данных.
 */
@Service
public class RefreshAheadProductService implements LabCache {

    public static final Duration REFRESH_AFTER = Duration.ofMinutes(1);
    public static final Duration EXPIRE_AFTER = Duration.ofMinutes(10);

    private final AsyncLoadingCache<Long, Product> cache;

    public RefreshAheadProductService(ProductSource source, Ticker cacheTicker) {
        this.cache = Caffeine.newBuilder()
                .ticker(cacheTicker)
                .maximumSize(10_000)
                .refreshAfterWrite(REFRESH_AFTER)
                .expireAfterWrite(EXPIRE_AFTER)
                .<Long, Product>buildAsync((key, executor) -> source.load(key).toFuture());
    }

    public Mono<Product> findById(long id) {
        return Mono.fromFuture(() -> cache.get(id), true);
    }

    /* Возраст записи по часам кэша; пока загрузка не завершилась — пусто. */
    public Optional<Duration> ageOf(long id) {
        return cache.synchronous().policy().refreshAfterWrite().flatMap(refresh -> refresh.ageOf(id));
    }

    @Override
    public void invalidateAll() {
        cache.synchronous().invalidateAll();
    }
}
