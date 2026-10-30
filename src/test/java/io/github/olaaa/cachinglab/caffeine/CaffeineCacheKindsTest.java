package io.github.olaaa.cachinglab.caffeine;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/*
 * Четыре вида кэшей Caffeine и роль загрузчика (loader). Без Spring и без БД:
 * «источник» — метод loadFromSource, который считает обращения.
 * Пояснения — в docs/caffeine-loader.md.
 */
class CaffeineCacheKindsTest {

    private final AtomicInteger sourceCalls = new AtomicInteger();

    private String loadFromSource(Long id) {
        sourceCalls.incrementAndGet();
        return "товар " + id;
    }

    /* Cache: загрузчика нет, кэш — просто хранилище. Загружает вызывающий код. */
    @Nested
    class ManualCache {

        @Test
        void cacheWithoutLoaderDoesNotLoadByItself() {
            Cache<Long, String> cache = Caffeine.newBuilder().maximumSize(100).build();

            assertThat(cache.getIfPresent(1L))
                    .as("промах: кэш не знает, откуда взять значение")
                    .isNull();

            cache.put(1L, loadFromSource(1L));

            assertThat(cache.getIfPresent(1L)).isEqualTo("товар 1");
        }

        @Test
        void mappingFunctionIsPassedOnEveryCall() {
            Cache<Long, String> cache = Caffeine.newBuilder().maximumSize(100).build();

            /* Функция загрузки передаётся в каждый вызов get, кэш её не запоминает. */
            assertThat(cache.get(1L, CaffeineCacheKindsTest.this::loadFromSource)).isEqualTo("товар 1");
            assertThat(cache.get(1L, CaffeineCacheKindsTest.this::loadFromSource)).isEqualTo("товар 1");

            assertThat(sourceCalls).hasValue(1);
        }
    }

    /* LoadingCache: загрузчик отдан один раз при создании, дальше просто get(key). */
    @Nested
    class LoadingCacheExamples {

        @Test
        void loaderIsGivenOnceAtBuildTime() {
            LoadingCache<Long, String> cache = Caffeine.newBuilder()
                    .maximumSize(100)
                    .build(CaffeineCacheKindsTest.this::loadFromSource);

            assertThat(cache.get(1L)).isEqualTo("товар 1");
            assertThat(cache.get(1L)).isEqualTo("товар 1");

            assertThat(sourceCalls).hasValue(1);
        }

        @Test
        void getAllLoadsMissingKeysWithOneBulkCall() {
            var bulkCalls = new AtomicInteger();
            var loader = new CacheLoader<Long, String>() {

                @Override
                public String load(Long id) {
                    return loadFromSource(id);
                }

                /* Без переопределения loadAll вызывает load для каждого ключа по очереди. */
                @Override
                public Map<Long, String> loadAll(Set<? extends Long> ids) {
                    bulkCalls.incrementAndGet();
                    return ids.stream().collect(toMap(Function.identity(), id -> "товар " + id));
                }
            };
            LoadingCache<Long, String> cache = Caffeine.newBuilder().maximumSize(100).build(loader);

            cache.get(1L);
            Map<Long, String> products = cache.getAll(List.of(1L, 2L, 3L));

            assertThat(products).containsOnlyKeys(1L, 2L, 3L);
            assertThat(sourceCalls).as("товар 1 загружен через load").hasValue(1);
            assertThat(bulkCalls).as("товары 2 и 3 — одним loadAll").hasValue(1);
        }

        @Test
        void loaderReturningNullMeansNoEntry() {
            LoadingCache<Long, String> cache = Caffeine.newBuilder()
                    .maximumSize(100)
                    .build(id -> {
                        sourceCalls.incrementAndGet();
                        return null;
                    });

            assertThat(cache.get(404L)).isNull();
            assertThat(cache.get(404L)).isNull();

            assertThat(sourceCalls)
                    .as("null не кэшируется: каждый запрос снова идёт в источник")
                    .hasValue(2);
        }

        @Test
        void refreshAfterWriteRequiresLoader() {
            assertThatThrownBy(() -> Caffeine.newBuilder().refreshAfterWrite(Duration.ofMinutes(1)).build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refreshAfterWrite requires a LoadingCache");
        }

        @Test
        void refreshReloadsValueWithTheSameLoader() {
            var version = new AtomicInteger();
            LoadingCache<Long, String> cache = Caffeine.newBuilder()
                    /* Перезагрузка в том же потоке — чтобы тест был детерминированным. */
                    .executor(Runnable::run)
                    .build(id -> "версия " + version.incrementAndGet());

            assertThat(cache.get(1L)).isEqualTo("версия 1");

            /* Клиент ничего не передаёт: кэш сам знает, как перечитать значение. */
            cache.refresh(1L).join();

            assertThat(cache.get(1L)).isEqualTo("версия 2");
        }
    }

    /* AsyncCache: загрузчика нет, значения — CompletableFuture. */
    @Nested
    class AsyncCacheExamples {

        @Test
        void concurrentCallersShareOnePendingFuture() {
            AsyncCache<Long, String> cache = Caffeine.newBuilder().maximumSize(100).buildAsync();
            var pendingLoad = new CompletableFuture<String>();

            CompletableFuture<String> firstCaller = cache.get(1L, (id, executor) -> {
                sourceCalls.incrementAndGet();
                return pendingLoad;
            });
            CompletableFuture<String> secondCaller = cache.get(1L, (id, executor) -> {
                sourceCalls.incrementAndGet();
                return pendingLoad;
            });

            assertThat(firstCaller).isNotDone();
            assertThat(sourceCalls)
                    .as("второй вызов не запустил загрузку — получил ту же future")
                    .hasValue(1);

            pendingLoad.complete("товар 1");

            assertThat(secondCaller.join()).isEqualTo("товар 1");
        }
    }

    /* AsyncLoadingCache: загрузчик отдан при создании, значения — CompletableFuture. */
    @Nested
    class AsyncLoadingCacheExamples {

        @Test
        void asyncLoaderReturnsFuture() {
            /* AsyncCacheLoader: (ключ, executor) -> future. Так подключают неблокирующий источник. */
            AsyncLoadingCache<Long, String> cache = Caffeine.newBuilder()
                    .maximumSize(100)
                    .buildAsync((id, executor) -> CompletableFuture.supplyAsync(() -> loadFromSource(id), executor));

            assertThat(cache.get(1L).join()).isEqualTo("товар 1");
            assertThat(cache.get(1L).join()).isEqualTo("товар 1");
            /* synchronous() — тот же кэш, но с блокирующим API. */
            assertThat(cache.synchronous().get(1L)).isEqualTo("товар 1");

            assertThat(sourceCalls).hasValue(1);
        }

        @Test
        void blockingLoaderRunsOnExecutorNotOnCallerThread() {
            var loaderThreadName = new AtomicReference<String>();
            /* buildAsync(CacheLoader): обычный блокирующий загрузчик, Caffeine сам запускает его на executor. */
            AsyncLoadingCache<Long, String> cache = Caffeine.newBuilder()
                    .maximumSize(100)
                    .buildAsync(id -> {
                        loaderThreadName.set(Thread.currentThread().getName());
                        return loadFromSource(id);
                    });

            assertThat(cache.get(1L).join()).isEqualTo("товар 1");

            assertThat(loaderThreadName.get()).isNotEqualTo(Thread.currentThread().getName());
        }
    }
}
