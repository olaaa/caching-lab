package io.github.olaaa.cachinglab.config;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.olaaa.cachinglab.scenario.AnnotatedProductService;
import io.github.olaaa.cachinglab.scenario.SyncCacheableProductService;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableCaching // чтобы работала аннотация @Cacheable(CACHE_NAME)
public class CacheConfiguration {

    /*
     * Часы для всех ручных кэшей Caffeine. В тестах их подменяет ManualTicker,
     * поэтому TTL и refreshAfterWrite проверяются без sleep.
     */
    @Bean
    Ticker cacheTicker() {
        return Ticker.systemTicker();
    }

    /*
     * Менеджер для @Cacheable (сценарии 3 и 9). Без setAsyncCacheMode(true) Spring не умеет
     * кэшировать Mono и Flux в Caffeine: реактивный @Cacheable опирается на Cache.retrieve,
     * а его поддерживает только асинхронный режим.
     */
    @Bean
    CaffeineCacheManager cacheManager() {
        var cacheManager = new CaffeineCacheManager(AnnotatedProductService.CACHE_NAME,
                SyncCacheableProductService.CACHE_NAME);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats());
        cacheManager.setAsyncCacheMode(true);
        return cacheManager;
    }
}
