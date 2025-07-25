/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.script;

import org.elasticsearch.core.TimeValue;

import java.util.Map;

/**
 * A script used by {@link org.elasticsearch.ingest.ConditionalProcessor}.
 */
public abstract class IngestConditionalScript extends SourceMapFieldScript {

    public static final String[] PARAMETERS = {};

    /**
     * The context used to compile {@link IngestConditionalScript} factories.
     * */
    public static final ScriptContext<Factory> CONTEXT = new ScriptContext<>(
        "processor_conditional",
        Factory.class,
        200,
        TimeValue.timeValueMillis(0),
        false,
        true
    );

    /**
     * The generic runtime parameters for the script.
     */
    private final Map<String, Object> params;

    public IngestConditionalScript(Map<String, Object> params, Map<String, Object> ctxMap) {
        super(ctxMap);
        this.params = params;
    }

    /**
     * Provides backwards compatibility access to ctx
     * @return the context map containing the source data
     */
    public Map<String, Object> getCtx() {
        return ctxMap;
    }

    /**
     * Return the parameters for this script.
     * @return a map of parameters
     */
    public Map<String, Object> getParams() {
        return params;
    }

    public abstract boolean execute();

    public interface Factory {
        IngestConditionalScript newInstance(Map<String, Object> params, Map<String, Object> ctxMap);
    }
}
