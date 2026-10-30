package io.github.olaaa.cachinglab.catalog;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/*
 * Источник истины для всех сценариев — «дорогая» загрузка товара.
 * Считает обращения (главная метрика лаборатории: сколько раз мы дошли до БД),
 * добавляет задержку, чтобы промах был заметен, и умеет притворяться упавшим.
 */
@Component
public class ProductSource {

    private final ProductRepository repository;
    private final Duration delay;
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile boolean failing;

    public ProductSource(ProductRepository repository, @Value("${catalog.source.delay}") Duration delay) {
        this.repository = repository;
        this.delay = delay;
    }

    public Mono<Product> load(long id) {
        /* Mono.defer: обращение считается в момент подписки, а не в момент сборки цепочки. */
        return Mono.defer(() -> {
            callCount.incrementAndGet();
            if (failing) {
                return Mono.error(new SourceUnavailableException(id));
            }
            return Mono.delay(delay).then(repository.findById(id));
        });
    }

    public int callCount() {
        return callCount.get();
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        callCount.set(0);
        failing = false;
    }
}
