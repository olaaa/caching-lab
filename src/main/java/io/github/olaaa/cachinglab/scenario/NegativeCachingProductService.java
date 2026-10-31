package io.github.olaaa.cachinglab.scenario;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/*
 * Сценарий 8. Отрицательный кэш: запоминаем и «товара нет».
 *
 * Без этого запросы несуществующих id (опечатки, перебор, удалённые товары) каждый раз
 * идут в источник — это называют cache penetration. Значение — Optional: пустой Optional
 * в отличие от null Caffeine хранит. «Нет» живёт коротко (NOT_FOUND_TTL), чтобы новый
 * товар не прятался долго.
 */
@Service
public class NegativeCachingProductService implements LabCache {

    public static final Duration FOUND_TTL = Duration.ofMinutes(10);
    public static final Duration NOT_FOUND_TTL = Duration.ofSeconds(30);

    private final ProductSource source;
    private final AsyncCache<Long, Optional<Product>> cache;

    public NegativeCachingProductService(ProductSource source, Ticker cacheTicker) {
        this.source = source;
        this.cache = Caffeine.newBuilder()
                .ticker(cacheTicker)
                .maximumSize(10_000)
                .expireAfter(Expiry.<Long, Optional<Product>>writing(
                        (key, value) -> value.isPresent() ? FOUND_TTL : NOT_FOUND_TTL))
                .buildAsync();
    }

    public Mono<Product> findById(long id) {
        return Mono.fromFuture(() -> cache.get(id,
                        (key, executor) -> source.load(key)
                                .map(value -> Optional.of(value))
                                .defaultIfEmpty(Optional.empty())
                                .toFuture()
                ), true)
                .flatMap(Mono::justOrEmpty);
    }

    /*
     * Сколько записи осталось жить. Пока загрузка не завершилась, Caffeine держит запись
     * с огромным сроком, поэтому здесь видно и то, что загрузка ещё идёт.
     */
    public Optional<Duration> expiresAfter(long id) {
        return cache.synchronous().policy().expireVariably()
                .flatMap(expiration -> expiration.getExpiresAfter(id));
    }

    @Override
    public void invalidateAll() {
        cache.synchronous().invalidateAll();
    }
}
