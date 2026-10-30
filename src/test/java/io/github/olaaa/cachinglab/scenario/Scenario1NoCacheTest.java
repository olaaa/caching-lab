package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import io.github.olaaa.cachinglab.catalog.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/* Сценарий 1. Без кэша: каждое чтение — обращение к источнику. */
class Scenario1NoCacheTest extends ScenarioTestSupport {

    private static final BigDecimal NEW_PRICE = new BigDecimal("2590.00");

    @Autowired
    NoCacheProductService noCacheProductService;

    @Test
    void everyReadGoesToSource() {
        for (int readNumber = 0; readNumber < 10; readNumber++) {
            assertThat(noCacheProductService.findById(KETTLE_ID).block()).isNotNull();
        }

        assertThat(source.callCount()).isEqualTo(10);
    }

    /*
     * Корректность счётчика: он считает подписки, то есть реальные обращения к источнику,
     * а не вызовы метода. Оба теста ниже падают, если убрать Mono.defer в ProductSource.load:
     * тогда счётчик увеличивается при сборке цепочки, один раз на вызов load.
     */

    @Test
    void sourceIsNotCalledWithoutSubscription() {
        /* Вызов findById только собирает цепочку. Подписки нет — обращения к источнику нет. */
        Mono<Product> notSubscribed = noCacheProductService.findById(KETTLE_ID);

        assertThat(notSubscribed).isNotNull();
        assertThat(source.callCount()).isZero();
    }

    @Test
    void everySubscriptionIsCounted() {
        /*
         * Один вызов findById и две подписки на результат. Запрос через DatabaseClient ленивый,
         * поэтому SELECT выполняется при каждой подписке. Обращений к источнику два, и счётчик
         * должен насчитать два.
         *
         * Смена цены между подписками для проверки счётчика не нужна: без Mono.defer тест падает
         * на последней строке и без неё. Она даёт независимое от счётчика доказательство, что
         * ожидаемое значение 2 — правда. Без неё тест говорит только «счётчик равен 2», и не видно,
         * кто ошибается без defer: может, с defer счётчик считает лишнее, а в БД сходили один раз,
         * просто значение использовали дважды. Вторая подписка вернула новую цену — значит, SELECT
         * выполнился заново, в БД сходили два раза, и счётчик без defer (1) занижает число
         * обращений. Тест сверяет счётчик с фактом, а не с самим собой.
         */
        Mono<Product> product = noCacheProductService.findById(KETTLE_ID);

        assertThat(product.block().price()).isEqualByComparingTo(KETTLE_PRICE);
        repository.updatePrice(KETTLE_ID, NEW_PRICE).block();
        assertThat(product.block().price()).isEqualByComparingTo(NEW_PRICE);

        assertThat(source.callCount()).isEqualTo(2);
    }
}
