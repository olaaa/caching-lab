package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.LongFunction;

import io.github.olaaa.cachinglab.catalog.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/*
 * Сценарий 6. Лавина промахов (cache stampede): 200 одновременных запросов
 * к одному холодному ключу.
 */
class Scenario6StampedeTest extends ScenarioTestSupport {

    private static final int CONCURRENT_REQUESTS = 200;

    @Autowired
    NaiveCacheAsideProductService naiveCacheAsideProductService;

    @Autowired
    CacheAsideProductService cacheAsideProductService;

    @Test
    void naiveCacheLetsEveryConcurrentMissThrough() {
        List<Product> products = readConcurrently(naiveCacheAsideProductService::findById);

        assertThat(products).hasSize(CONCURRENT_REQUESTS);
        assertThat(source.callCount())
                .as("все 200 запросов увидели пустой кэш раньше, чем первый успел его заполнить")
                .isEqualTo(CONCURRENT_REQUESTS);
    }

//    Асинхронный кэш объединяет одновременные промахи
    @Test
    void asyncCacheCoalescesConcurrentMisses() {
        List<Product> products = readConcurrently(cacheAsideProductService::findById);

        assertThat(products).hasSize(CONCURRENT_REQUESTS);
        assertThat(source.callCount())
                .as("все ждут одну и ту же future первой загрузки")
                .isEqualTo(1);
    }

    private static List<Product> readConcurrently(LongFunction<Mono<Product>> reader) {
        /* flatMap подписывается на все 200 запросов сразу, не дожидаясь ответов. */
        return Flux.range(0, CONCURRENT_REQUESTS)
                .flatMap(requestNumber -> reader.apply(KETTLE_ID), CONCURRENT_REQUESTS)
                .collectList()
                .block();
    }
}
