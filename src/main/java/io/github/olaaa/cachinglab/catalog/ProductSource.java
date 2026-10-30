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
    /**
     * Число обращений к источнику.
     * <p>
     * {@code ProductSource} — один бин на всё приложение, и {@link #load(long)} вызывают из многих
     * потоков сразу: потоки event loop Reactor Netty (по одному на HTTP-запрос), потоки планировщика
     * Reactor после {@code Mono.delay}, потоки {@code ForkJoinPool.commonPool()}, на которых Caffeine
     * запускает фоновую перезагрузку (сценарий 7), и главный поток теста. В сценарии 6 до двухсот
     * подписок увеличивают счётчик почти одновременно.
     * <p>
     * Обычный {@code int} с {@code callCount++} здесь не годится: {@code ++} — это три шага
     * (прочитать, прибавить, записать), и два потока могут прочитать одно и то же значение — одно
     * увеличение потеряется, и тест увидит меньше обращений, чем было. {@code volatile int} не
     * спасает: он гарантирует видимость, но не делает {@code ++} одной операцией.
     * {@link AtomicInteger#incrementAndGet()} выполняет увеличение атомарно (через compare-and-set)
     * и заодно гарантирует, что поток теста увидит все увеличения.
     */
    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * Притворяется ли источник упавшим (сценарий 7).
     * <p>
     * Флаг меняет один поток (тест или запрос {@code POST /lab/source/fail}), а читают другие —
     * те, что выполняют {@link #load(long)}. Без {@code volatile} модель памяти Java не обещает,
     * что читающий поток когда-нибудь увидит новое значение: оно может остаться в кэше процессора,
     * а JIT вправе вынести чтение поля из цикла. {@code volatile} даёт видимость: запись
     * происходит-до (happens-before) любого последующего чтения этого поля в другом потоке.
     * <p>
     * Атомарность здесь не нужна: с флагом делают только простую запись и простое чтение, без
     * «прочитать и изменить», поэтому {@code AtomicBoolean} с его compare-and-set был бы лишним.
     */
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
