# caching-lab

Учебный проект: каталог товаров, на котором каждый приём кэширования воспроизводится отдельным тестом. Главная метрика везде одна — сколько раз запрос дошёл до источника (`ProductSource.callCount()`).

Стек: Java 25, Spring Boot 4.1, WebFlux, R2DBC (`DatabaseClient`), Reactor, Caffeine, PostgreSQL в Testcontainers.

## Как запустить

Нужны JDK 25, Maven и запущенный Docker.

```bash
mvn test
```

Тесты поднимают один контейнер PostgreSQL на все сценарии. Время для TTL и `refreshAfterWrite` двигает `ManualTicker`, поэтому `sleep` в тестах нет.

Ручной запуск:

```bash
mvn spring-boot:run
```

Spring Boot сам поднимет PostgreSQL из `compose.yaml`. Запросы для IntelliJ HTTP Client — в папке `http`, по файлу на сценарий, счётчик обращений к источнику — `GET /lab/stats`, сброс — `POST /lab/reset`.

## Устройство

- `catalog/ProductRepository` — SQL через `DatabaseClient`, без кэша.
- `catalog/ProductSource` — «дорогой» источник: ходит в репозиторий с задержкой, считает обращения, умеет падать.
- `scenario/*` — по сервису на приём кэширования.
- `web/ProductController` — `GET /products/{id}?via=…` выбирает сервис, `PUT /products/{id}/price` меняет цену.

Тонкость тестов со временем. `AsyncCache` отмечает время записи в колбэке future, когда загрузка завершилась, а колбэки `CompletableFuture` выполняются в обратном порядке регистрации. Поэтому `block()` может вернуть товар раньше, чем Caffeine запишет время. Если сразу сдвинуть `ManualTicker`, запись получит время уже после сдвига. Тесты перед сдвигом часов ждут, пока у записи появится возраст (`awaitEntryWritten`).

## Перед сценариями: какие бывают кэши Caffeine

Тест: `caffeine/CaffeineCacheKindsTest`. Текст: [docs/caffeine-loader.md](docs/caffeine-loader.md).

Что такое загрузчик (loader) и загружающий кэш (`LoadingCache`, `AsyncLoadingCache`), чем они отличаются от `Cache` и `AsyncCache` и зачем загрузчик нужен: одно место загрузки, защита от лавины промахов, `getAll` одним запросом, фоновое обновление через `refreshAfterWrite`. Тест работает без Spring и без БД.

## Этап 1. Локальный кэш

Подробное описание каждого сценария и оценка, какие из них чаще встречаются в продакшене, — [docs/scenarios](docs/scenarios/README.md). Ниже — коротко.

### 1. Без кэша

Тест: `Scenario1NoCacheTest`. Сервис: `NoCacheProductService`. Подробно: [docs/scenarios/01-no-cache.md](docs/scenarios/01-no-cache.md), вручную: `http/01-no-cache.http`.

Десять чтений — десять обращений к источнику. Точка отсчёта для остальных сценариев.

### 2. Cache-Aside на AsyncCache и доля попаданий

Тест: `Scenario2CacheAsideTest`. Сервис: `CacheAsideProductService`. Подробно: [docs/scenarios/02-cache-aside.md](docs/scenarios/02-cache-aside.md), вручную: `http/02-cache-aside.http`.

Десять чтений — одно обращение, доля попаданий 0,9. В `AsyncCache` лежит не товар, а `CompletableFuture` с товаром. Статистику включает `recordStats()`, в Micrometer её отдаёт `CaffeineCacheMetrics.monitor` (метрика `cache.gets` с тегом `result`).

Что заметить:

- `Mono.fromFuture(supplier, true)`: supplier — чтобы кэш читался при подписке, `true` — чтобы отмена одного подписчика не отменяла общую future.
- Статистика Caffeine накопительная, сбросить её нельзя; в тесте считается разница до и после.

### 3. `@Cacheable` на `Mono` и вызов изнутри бина

Тест: `Scenario3CacheableTest`. Сервисы: `AnnotatedProductService`, `ProductPriceQueries`. Подробно: [docs/scenarios/03-cacheable.md](docs/scenarios/03-cacheable.md), вручную: `http/03-cacheable.http`.

С Spring Framework 6.1 `@Cacheable` умеет кэшировать значение `Mono` и `Flux`, а не сам объект `Mono`. Для Caffeine нужен `CaffeineCacheManager.setAsyncCacheMode(true)` — см. `CacheConfiguration`.

Ловушка: `findPrice` вызывает `findById` через `this`, мимо прокси Spring, и кэш не работает — каждый вызов идёт в источник. Тот же вызов из другого бина (`ProductPriceQueries`) идёт через прокси и попадает в кэш.

### 4. TTL и устаревание

Тест: `Scenario4TimeToLiveTest`. Подробно: [docs/scenarios/04-time-to-live.md](docs/scenarios/04-time-to-live.md), вручную: `http/04-time-to-live.http`.

Цена в БД меняется в обход кэша. Пока не истёк TTL (`expireAfterWrite`, 5 минут), кэш отдаёт старую цену. TTL — верхняя граница того, насколько устаревшими могут быть данные, если инвалидации нет или она потерялась.

### 5. Инвалидация до коммита и после

Тест: `Scenario5InvalidationTest`. Сервис: `ProductPriceService`. Подробно: [docs/scenarios/05-invalidation.md](docs/scenarios/05-invalidation.md), вручную: `http/05-invalidation.http`.

- Удалить из кэша до коммита — ошибка. Параллельное чтение между удалением и коммитом загружает из БД старую цену и кладёт её в кэш. После коммита в БД новая цена, в кэше старая — до конца TTL.
- Удалить после коммита — правильно. Даже если кто-то успел прочитать старую цену во время транзакции, удаление после коммита её убирает.

Как гонка воспроизводится без потоков: чтение через кэш стоит прямо внутри `TransactionalOperator`. Загрузчик кэша подписывается через `toFuture()` без Reactor Context, а транзакция в реактивном Spring живёт именно в контексте. Поэтому загрузчик идёт по другому соединению и видит только закоммиченную цену — как настоящий параллельный запрос.

Что заметить: в Caffeine `invalidate` во время идущей загрузки убирает и её future, так что запоздавший ответ не вернёт старое значение в кэш. В Redis такой защиты нет — это тема этапа 2.

### 6. Лавина промахов

Тест: `Scenario6StampedeTest`. Сервисы: `NaiveCacheAsideProductService`, `CacheAsideProductService`. Подробно: [docs/scenarios/06-stampede.md](docs/scenarios/06-stampede.md), вручную: `http/06-stampede.http`.

200 одновременных запросов к холодному ключу:

- наивный кэш («нет в кэше — загрузи и положи») — 200 обращений к источнику: все увидели пустой кэш раньше, чем первый его заполнил;
- `AsyncCache.get(key, loader)` — одно обращение: остальные ждут ту же future.

В `@Cacheable` то же даёт `sync = true`.

### 7. Refresh-Ahead и упавший источник

Тест: `Scenario7RefreshAheadTest`. Сервис: `RefreshAheadProductService`. Подробно: [docs/scenarios/07-refresh-ahead.md](docs/scenarios/07-refresh-ahead.md), вручную: `http/07-refresh-ahead.http`.

`refreshAfterWrite` (1 минута) плюс `expireAfterWrite` (10 минут):

- после минуты первое чтение сразу получает старое значение, а Caffeine перезагружает запись в фоне — клиент не ждёт источник;
- если источник упал, фоновая перезагрузка падает, а кэш продолжает отдавать старое значение;
- через 10 минут запись удаляется, и ошибка источника доходит до клиента. `expireAfterWrite` ограничивает возраст данных, которые кэш готов отдавать.

### 8. Отрицательный кэш

Тест: `Scenario8NegativeCachingTest`. Сервис: `NegativeCachingProductService`. Подробно: [docs/scenarios/08-negative-cache.md](docs/scenarios/08-negative-cache.md), вручную: `http/08-negative-cache.http`.

Обычный `AsyncCache` не хранит `null`, поэтому запросы несуществующего товара каждый раз идут в источник (cache penetration). Отрицательный кэш хранит `Optional.empty()` с коротким TTL (30 секунд, через `Expiry.writing`), найденный товар — с длинным (10 минут).

Цена: новый товар не виден, пока не истечёт отрицательная запись. Поэтому TTL для «нет» короткий, а при создании товара запись надо удалять.

## Дальше

- Этап 2. Redis как кэш: `RedisCacheManager`, сериализация, TTL с разбросом, двухуровневый кэш, инвалидация между репликами.
- Этап 3. Redis не как кэш: ограничение частоты запросов, распределённая блокировка с арендой, идемпотентность.
- Отдельный сюжет — курсы валют: данные, которые меняются по расписанию и допускают устаревание.
