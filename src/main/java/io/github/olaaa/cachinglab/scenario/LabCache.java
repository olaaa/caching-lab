package io.github.olaaa.cachinglab.scenario;

/* Общий вход для сброса ручных кэшей между тестами и через POST /lab/reset. */
public interface LabCache {

    void invalidateAll();
}
