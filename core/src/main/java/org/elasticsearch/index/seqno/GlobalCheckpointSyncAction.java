/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.seqno;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.replication.ReplicationOperation;
import org.elasticsearch.action.support.replication.ReplicationRequest;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.support.replication.TransportReplicationAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 * Background global checkpoint sync action initiated when a shard goes inactive. This is needed because while we send the global checkpoint
 * on every replication operation, after the last operation completes the global checkpoint could advance but without a follow-up operation
 * the global checkpoint will never be synced to the replicas.
 */
public class GlobalCheckpointSyncAction extends TransportReplicationAction<
        GlobalCheckpointSyncAction.Request,
        GlobalCheckpointSyncAction.Request,
        ReplicationResponse> implements IndexEventListener {

    public static String ACTION_NAME = "indices:admin/seq_no/global_checkpoint_sync";

    @Inject
    public GlobalCheckpointSyncAction(
            final Settings settings,
            final TransportService transportService,
            final ClusterService clusterService,
            final IndicesService indicesService,
            final ThreadPool threadPool,
            final ShardStateAction shardStateAction,
            final ActionFilters actionFilters,
            final IndexNameExpressionResolver indexNameExpressionResolver) {
        super(
                settings,
                ACTION_NAME,
                transportService,
                clusterService,
                indicesService,
                threadPool,
                shardStateAction,
                actionFilters,
                indexNameExpressionResolver,
                Request::new,
                Request::new,
                ThreadPool.Names.SAME);
    }

    @Override
    protected ReplicationResponse newResponseInstance() {
        return new ReplicationResponse();
    }

    @Override
    protected void sendReplicaRequest(
            final ConcreteReplicaRequest<Request> replicaRequest,
            final DiscoveryNode node,
            final ActionListener<ReplicationOperation.ReplicaResponse> listener) {
        if (node.getVersion().onOrAfter(Version.V_6_0_0_alpha1)) {
            super.sendReplicaRequest(replicaRequest, node, listener);
        } else {
            listener.onResponse(new ReplicaResponse(SequenceNumbersService.UNASSIGNED_SEQ_NO));
        }
    }

    @Override
    public void onShardInactive(final IndexShard indexShard) {
        execute(new Request(indexShard.shardId()));
    }

    @Override
    protected PrimaryResult<Request, ReplicationResponse> shardOperationOnPrimary(
            final Request request, final IndexShard indexShard) throws Exception {
        indexShard.getTranslog().sync();
        return new PrimaryResult<>(request, new ReplicationResponse());
    }

    @Override
    protected ReplicaResult shardOperationOnReplica(final Request request, final IndexShard indexShard) throws Exception {
        indexShard.getTranslog().sync();
        return new ReplicaResult();
    }

    public static final class Request extends ReplicationRequest<Request> {

        private Request() {
            super();
        }

        public Request(final ShardId shardId) {
            super(shardId);
        }

        @Override
        public String toString() {
            return "GlobalCheckpointSyncAction.Request{" +
                    "shardId=" + shardId +
                    ", timeout=" + timeout +
                    ", index='" + index + '\'' +
                    ", waitForActiveShards=" + waitForActiveShards +
                    "}";
        }
    }

}
