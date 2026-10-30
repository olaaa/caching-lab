package io.github.olaaa.cachinglab.catalog;

import java.math.BigDecimal;

public record Product(long id, String name, BigDecimal price) {
}
