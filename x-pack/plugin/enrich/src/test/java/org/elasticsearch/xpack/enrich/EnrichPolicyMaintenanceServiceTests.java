/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.enrich;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexResponse;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.enrich.EnrichPolicy;

import static org.elasticsearch.xpack.core.enrich.EnrichPolicy.MATCH_TYPE;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;

public class EnrichPolicyMaintenanceServiceTests extends ESSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return List.of(LocalStateEnrich.class);
    }

    private int indexNameAutoIncrementingCounter = 0;

    public void testIndexRemoval() throws Exception {
        // Create a test enabled maintenance service
        EnrichPolicyMaintenanceService maintenanceService = createMaintenanceService();

        // Add some random policies for the maintenance thread to reference
        addPolicy("policy1", randomPolicy());
        addPolicy("policy2", randomPolicy());

        // Create some indices for the policies
        Set<String> expectedIndices = new HashSet<>();
        String policy1Index1 = fakeRunPolicy("policy1");
        expectedIndices.add(policy1Index1);
        String policy2Index1 = fakeRunPolicy("policy2");
        expectedIndices.add(policy2Index1);

        // Ensure that the expected indices exist
        assertEnrichIndicesExist(expectedIndices);

        // Do cleanup - shouldn't find anything to clean up
        maintenanceService.cleanUpEnrichIndices();

        // Ensure that the expected indices still exist
        assertEnrichIndicesExist(expectedIndices);

        // Replace a policy index with a new one
        String policy1Index2 = fakeRunPolicy("policy1");
        expectedIndices.add(policy1Index2);

        // Ensure all three indices exist
        assertEnrichIndicesExist(expectedIndices);

        // Should clean up the first index for the first policy
        maintenanceService.cleanUpEnrichIndices();

        // Ensure only the two most recent indices exist
        expectedIndices.remove(policy1Index1);
        assertEnrichIndicesExist(expectedIndices);

        // Remove a policy to simulate an abandoned index with a valid alias, but no policy
        removePolicy("policy2");

        // Should cleanup the first index for the second policy
        maintenanceService.cleanUpEnrichIndices();

        // Ensure only the first policy's index is left
        expectedIndices.remove(policy2Index1);
        assertEnrichIndicesExist(expectedIndices);

        // Clean up the remaining policy indices
        removePolicy("policy1");
        maintenanceService.cleanUpEnrichIndices();
        expectedIndices.remove(policy1Index2);
        assertEnrichIndicesExist(expectedIndices);
    }

    private void assertEnrichIndicesExist(Set<String> activeIndices) {
        GetIndexResponse indices = client().admin().indices().getIndex(new GetIndexRequest().indices(".enrich-*")).actionGet();
        assertThat(indices.indices().length, is(equalTo(activeIndices.size())));
        for (String index : indices.indices()) {
            assertThat(activeIndices.contains(index), is(true));
        }
    }

    private EnrichPolicy randomPolicy() {
        List<String> enrichKeys = new ArrayList<>();
        for (int i = 0; i < randomIntBetween(1, 3); i++) {
            enrichKeys.add(randomAlphaOfLength(10));
        }
        return new EnrichPolicy(MATCH_TYPE, null, List.of(randomAlphaOfLength(10)), randomAlphaOfLength(10), enrichKeys);
    }

    private void addPolicy(String policyName, EnrichPolicy policy) throws InterruptedException {
        doSyncronously((clusterService, exceptionConsumer) ->
            EnrichStore.putPolicy(policyName, policy, clusterService, exceptionConsumer));
    }

    private void removePolicy(String policyName) throws InterruptedException {
        doSyncronously((clusterService, exceptionConsumer) ->
            EnrichStore.deletePolicy(policyName, clusterService, exceptionConsumer));
    }

    private void doSyncronously(BiConsumer<ClusterService, Consumer<Exception>> function) throws InterruptedException {
        ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<>(null);
        Consumer<Exception> waitingHandler = e -> {
            failure.set(e);
            latch.countDown();
        };
        function.accept(clusterService, waitingHandler);
        latch.await();
        Exception exception = failure.get();
        if (exception != null) {
            throw new RuntimeException("Exception while modifying policy", exception);
        }
    }

    private String fakeRunPolicy(String forPolicy) throws IOException {
        String newIndexName = EnrichPolicy.getBaseName(forPolicy) + "-" + indexNameAutoIncrementingCounter++;
        CreateIndexRequest request = new CreateIndexRequest(newIndexName)
            .mapping(
                MapperService.SINGLE_MAPPING_NAME, JsonXContent.contentBuilder()
                    .startObject()
                    .startObject(MapperService.SINGLE_MAPPING_NAME)
                    .startObject("_meta")
                    .field(EnrichPolicyRunner.ENRICH_POLICY_NAME_FIELD_NAME, forPolicy)
                    .endObject()
                    .endObject()
                    .endObject()
            );
        client().admin().indices().create(request).actionGet();
        promoteFakePolicyIndex(newIndexName, forPolicy);
        return newIndexName;
    }

    private void promoteFakePolicyIndex(String indexName, String forPolicy) {
        String enrichIndexBase = EnrichPolicy.getBaseName(forPolicy);
        GetAliasesResponse getAliasesResponse = client().admin().indices().getAliases(new GetAliasesRequest(enrichIndexBase)).actionGet();
        IndicesAliasesRequest aliasToggleRequest = new IndicesAliasesRequest();
        String[] indices = getAliasesResponse.getAliases().keys().toArray(String.class);
        if (indices.length > 0) {
            aliasToggleRequest.addAliasAction(IndicesAliasesRequest.AliasActions.remove().indices(indices).alias(enrichIndexBase));
        }
        aliasToggleRequest.addAliasAction(IndicesAliasesRequest.AliasActions.add().index(indexName).alias(enrichIndexBase));
        client().admin().indices().aliases(aliasToggleRequest).actionGet();
    }

    private EnrichPolicyMaintenanceService createMaintenanceService() {
        ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        ThreadPool threadPool = getInstanceFromNode(ThreadPool.class);
        // Extend the maintenance service to make the cleanUpEnrichIndices method a blocking method that waits for clean up to complete
        return new EnrichPolicyMaintenanceService(Settings.EMPTY, client(), clusterService, threadPool, new EnrichPolicyLocks()) {
            final Phaser completionBarrier = new Phaser(2);

            @Override
            void cleanUpEnrichIndices() {
                super.cleanUpEnrichIndices();
                completionBarrier.arriveAndAwaitAdvance();
            }

            @Override
            void concludeMaintenance() {
                super.concludeMaintenance();
                completionBarrier.arrive();
            }
        };
    }
}
