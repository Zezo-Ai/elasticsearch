/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.dataframe.inference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.metrics.Max;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.ClientHelper;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.dataframe.DestinationIndex;
import org.elasticsearch.xpack.ml.dataframe.stats.DataCountsTracker;
import org.elasticsearch.xpack.ml.dataframe.stats.ProgressTracker;
import org.elasticsearch.xpack.ml.extractor.ExtractedField;
import org.elasticsearch.xpack.ml.extractor.ExtractedFields;
import org.elasticsearch.xpack.ml.extractor.SourceSupplier;
import org.elasticsearch.xpack.ml.inference.loadingservice.LocalModel;
import org.elasticsearch.xpack.ml.inference.loadingservice.ModelLoadingService;
import org.elasticsearch.xpack.ml.utils.MlIndicesUtils;
import org.elasticsearch.xpack.ml.utils.persistence.LimitAwareBulkIndexer;
import org.elasticsearch.xpack.ml.utils.persistence.ResultsPersisterService;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.elasticsearch.core.Strings.format;

public class InferenceRunner {

    private static final Logger LOGGER = LogManager.getLogger(InferenceRunner.class);

    private static final int MAX_PROGRESS_BEFORE_COMPLETION = 98;

    private final Settings settings;
    private final Client client;
    private final ModelLoadingService modelLoadingService;
    private final ResultsPersisterService resultsPersisterService;
    private final TaskId parentTaskId;
    private final DataFrameAnalyticsConfig config;
    private final ExtractedFields extractedFields;
    private final ProgressTracker progressTracker;
    private final DataCountsTracker dataCountsTracker;
    private final Function<Long, TestDocsIterator> testDocsIteratorFactory;
    private final ThreadPool threadPool;
    private volatile boolean isCancelled;

    InferenceRunner(
        Settings settings,
        Client client,
        ModelLoadingService modelLoadingService,
        ResultsPersisterService resultsPersisterService,
        TaskId parentTaskId,
        DataFrameAnalyticsConfig config,
        ExtractedFields extractedFields,
        ProgressTracker progressTracker,
        DataCountsTracker dataCountsTracker,
        Function<Long, TestDocsIterator> testDocsIteratorFactory,
        ThreadPool threadPool
    ) {
        this.settings = Objects.requireNonNull(settings);
        this.client = Objects.requireNonNull(client);
        this.modelLoadingService = Objects.requireNonNull(modelLoadingService);
        this.resultsPersisterService = Objects.requireNonNull(resultsPersisterService);
        this.parentTaskId = Objects.requireNonNull(parentTaskId);
        this.config = Objects.requireNonNull(config);
        this.extractedFields = Objects.requireNonNull(extractedFields);
        this.progressTracker = Objects.requireNonNull(progressTracker);
        this.dataCountsTracker = Objects.requireNonNull(dataCountsTracker);
        this.testDocsIteratorFactory = Objects.requireNonNull(testDocsIteratorFactory);
        this.threadPool = threadPool;
    }

    public void cancel() {
        isCancelled = true;
    }

    public void run(String modelId, ActionListener<Void> listener) {
        if (isCancelled) {
            listener.onResponse(null);
            return;
        }

        LOGGER.info("[{}] Started inference on test data against model [{}]", config.getId(), modelId);
        SubscribableListener.<LocalModel>newForked(l -> modelLoadingService.getModelForInternalInference(modelId, l))
            .andThen(threadPool.executor(MachineLearning.UTILITY_THREAD_POOL_NAME), threadPool.getThreadContext(), this::handleLocalModel)
            .addListener(listener.delegateResponse((delegate, e) -> delegate.onFailure(handleException(modelId, e))));
    }

    private void handleLocalModel(ActionListener<Void> listener, LocalModel localModel) {
        try (localModel) {
            InferenceState inferenceState = restoreInferenceState();
            dataCountsTracker.setTestDocsCount(inferenceState.processedTestDocsCount);
            var testDocsIterator = testDocsIteratorFactory.apply(inferenceState.lastIncrementalId);
            LOGGER.debug("Loaded inference model [{}]", localModel);
            inferTestDocs(localModel, testDocsIterator, inferenceState.processedTestDocsCount);
            listener.onResponse(null); // void
        }
    }

    private Exception handleException(String modelId, Exception e) {
        LOGGER.error(() -> format("[%s] Error running inference on model [%s]", config.getId(), modelId), e);
        if (e instanceof ElasticsearchException elasticsearchException) {
            return new ElasticsearchStatusException(
                "[{}] failed running inference on model [{}]; cause was [{}]",
                elasticsearchException.status(),
                elasticsearchException.getRootCause(),
                config.getId(),
                modelId,
                elasticsearchException.getRootCause().getMessage()
            );
        } else {
            return ExceptionsHelper.serverError(
                "[{}] failed running inference on model [{}]; cause was [{}]",
                e,
                config.getId(),
                modelId,
                e.getMessage()
            );
        }
    }

    private InferenceState restoreInferenceState() {
        SearchRequest searchRequest = new SearchRequest(config.getDest().getIndex());
        searchRequest.indicesOptions(MlIndicesUtils.addIgnoreUnavailable(SearchRequest.DEFAULT_INDICES_OPTIONS));
        SearchSourceBuilder sourceBuilder = (new SearchSourceBuilder().size(0)
            .query(
                QueryBuilders.boolQuery()
                    .filter(QueryBuilders.termQuery(config.getDest().getResultsField() + "." + DestinationIndex.IS_TRAINING, false))
            )
            .fetchSource(false)
            .aggregation(AggregationBuilders.max(DestinationIndex.INCREMENTAL_ID).field(DestinationIndex.INCREMENTAL_ID))
            .trackTotalHits(true));
        searchRequest.source(sourceBuilder);

        SearchResponse searchResponse = ClientHelper.executeWithHeaders(
            config.getHeaders(),
            ClientHelper.ML_ORIGIN,
            client,
            client.search(searchRequest)::actionGet
        );
        try {
            Max maxIncrementalIdAgg = searchResponse.getAggregations().get(DestinationIndex.INCREMENTAL_ID);
            long processedTestDocCount = searchResponse.getHits().getTotalHits().value();
            Long lastIncrementalId = processedTestDocCount == 0 ? null : (long) maxIncrementalIdAgg.value();
            if (lastIncrementalId != null) {
                LOGGER.debug(
                    () -> format(
                        "[%s] Resuming inference; last incremental id [%s]; processed test doc count [%s]",
                        config.getId(),
                        lastIncrementalId,
                        processedTestDocCount
                    )
                );
            }
            return new InferenceState(lastIncrementalId, processedTestDocCount);
        } finally {
            searchResponse.decRef();
        }
    }

    private void inferTestDocs(LocalModel model, TestDocsIterator testDocsIterator, long processedTestDocsCount) {
        assert ThreadPool.assertCurrentThreadPool(MachineLearning.UTILITY_THREAD_POOL_NAME)
            : format(
                "inferTestDocs must execute from [MachineLearning.UTILITY_THREAD_POOL_NAME] but thread is [%s]",
                Thread.currentThread().getName()
            );
        long totalDocCount = 0;
        long processedDocCount = processedTestDocsCount;

        try (LimitAwareBulkIndexer bulkIndexer = new LimitAwareBulkIndexer(settings, this::executeBulkRequest)) {
            while (testDocsIterator.hasNext()) {
                if (isCancelled) {
                    break;
                }

                Deque<SearchHit> batch = testDocsIterator.next();

                if (totalDocCount == 0) {
                    totalDocCount = testDocsIterator.getTotalHits();
                }

                for (SearchHit doc : batch) {
                    dataCountsTracker.incrementTestDocsCount();
                    SourceSupplier sourceSupplier = new SourceSupplier(doc);
                    InferenceResults inferenceResults = model.inferNoStats(featuresFromDoc(doc, sourceSupplier));
                    bulkIndexer.addAndExecuteIfNeeded(
                        createIndexRequest(doc, sourceSupplier, inferenceResults, config.getDest().getResultsField())
                    );

                    processedDocCount++;
                    int progressPercent = Math.min((int) (processedDocCount * 100.0 / totalDocCount), MAX_PROGRESS_BEFORE_COMPLETION);
                    progressTracker.updateInferenceProgress(progressPercent);
                }
            }
        }

        if (isCancelled == false) {
            progressTracker.updateInferenceProgress(100);
        }
    }

    private Map<String, Object> featuresFromDoc(SearchHit doc, SourceSupplier sourceSupplier) {
        Map<String, Object> features = new HashMap<>();
        for (ExtractedField extractedField : extractedFields.getAllFields()) {
            Object[] values = extractedField.value(doc, sourceSupplier);
            if (values.length == 1) {
                features.put(extractedField.getName(), values[0]);
            }
        }
        return features;
    }

    private IndexRequest createIndexRequest(SearchHit hit, SourceSupplier sourceSupplier, InferenceResults results, String resultField) {
        Map<String, Object> resultsMap = new LinkedHashMap<>(results.asMap());
        resultsMap.put(DestinationIndex.IS_TRAINING, false);
        Map<String, Object> source = new LinkedHashMap<>(sourceSupplier.get());
        source.put(resultField, resultsMap);
        IndexRequest indexRequest = new IndexRequest(hit.getIndex());
        indexRequest.id(hit.getId());
        indexRequest.source(source);
        indexRequest.opType(DocWriteRequest.OpType.INDEX);
        indexRequest.setParentTask(parentTaskId);
        return indexRequest;
    }

    private void executeBulkRequest(BulkRequest bulkRequest) {
        resultsPersisterService.bulkIndexWithHeadersWithRetry(
            config.getHeaders(),
            bulkRequest,
            config.getId(),
            () -> isCancelled == false,
            retryMessage -> {}
        );
    }

    public static InferenceRunner create(
        Settings settings,
        Client client,
        ModelLoadingService modelLoadingService,
        ResultsPersisterService resultsPersisterService,
        TaskId parentTaskId,
        DataFrameAnalyticsConfig config,
        ExtractedFields extractedFields,
        ProgressTracker progressTracker,
        DataCountsTracker dataCountsTracker,
        ThreadPool threadPool
    ) {
        return new InferenceRunner(
            settings,
            client,
            modelLoadingService,
            resultsPersisterService,
            parentTaskId,
            config,
            extractedFields,
            progressTracker,
            dataCountsTracker,
            lastIncrementalId -> new TestDocsIterator(
                new OriginSettingClient(client, ClientHelper.ML_ORIGIN),
                config,
                extractedFields,
                lastIncrementalId
            ),
            threadPool
        );
    }

    private record InferenceState(@Nullable Long lastIncrementalId, long processedTestDocsCount) {}
}
