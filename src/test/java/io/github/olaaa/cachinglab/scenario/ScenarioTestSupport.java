package io.github.olaaa.cachinglab.scenario;

import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import io.github.olaaa.cachinglab.ManualTicker;
import io.github.olaaa.cachinglab.ManualTickerConfiguration;
import io.github.olaaa.cachinglab.TestcontainersConfiguration;
import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductRepository;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/*
 * Общая основа сценариев: один контекст Spring и один контейнер PostgreSQL на все тесты.
 * Перед каждым тестом — чистые кэши, обнулённый счётчик источника и одинаковые данные.
 */
@SpringBootTest(properties = "catalog.source.delay=100ms")
@Import({TestcontainersConfiguration.class, ManualTickerConfiguration.class})
abstract class ScenarioTestSupport {

    static final long KETTLE_ID = 1;
    static final BigDecimal KETTLE_PRICE = new BigDecimal("2490.00");
    static final long MISSING_ID = 404;
    static final int CONCURRENT_REQUESTS = 200;

    @Autowired
    ProductRepository repository;

    @Autowired
    ProductSource source;

    @Autowired
    ManualTicker ticker;

    @Autowired
    List<LabCache> labCaches;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void resetLab() {
        repository.deleteAll()
                .then(repository.save(new Product(KETTLE_ID, "Чайник", KETTLE_PRICE)))
                .block();
        labCaches.forEach(LabCache::invalidateAll);
        for (String cacheName : cacheManager.getCacheNames()) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
        source.reset();
    }

    /*
     * Ждём, пока Caffeine отметит время записи. AsyncCache делает это в колбэке future,
     * а колбэки CompletableFuture выполняются в обратном порядке регистрации: block()
     * может вернуть товар раньше, чем Caffeine запишет время. Если в этот промежуток
     * сдвинуть ManualTicker, запись получит время уже после сдвига и «помолодеет».
     */
    /*
     * 200 одновременных чтений одного холодного ключа (сценарии 6 и 9).
     * flatMap подписывается на все 200 запросов сразу, не дожидаясь ответов.
     */
    static List<Product> readConcurrently(LongFunction<Mono<Product>> reader) {
        return Flux.range(0, CONCURRENT_REQUESTS)
                .flatMap(requestNumber -> reader.apply(KETTLE_ID), CONCURRENT_REQUESTS)
                .collectList()
                .block();
    }

    static void awaitEntryWritten(Supplier<Optional<Duration>> ageOfEntry) {
        await().atMost(Duration.ofSeconds(5))
                .until(() -> ageOfEntry.get().filter(age -> !age.isNegative()).isPresent());
    }
}
