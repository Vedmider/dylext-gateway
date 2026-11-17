package io.github.vedmider.gateway.domain.model;

/** What a context provider can answer; the gateway routes by capability and tags telemetry with it. */
public enum Capability {
    STRUCTURE, SPAN, IMPACT, LIVE, CONCEPT, FUZZY, DOC, VERIFY, SIGNAL, INDEX, SESSION, METER
}
