/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.rocketmq.store.api;

import com.automq.rocketmq.common.model.generated.FlatMessage;
import com.automq.rocketmq.store.model.message.AckResult;
import com.automq.rocketmq.store.model.message.ChangeInvisibleDurationResult;
import com.automq.rocketmq.store.model.message.Filter;
import com.automq.rocketmq.store.model.message.PopResult;
import com.automq.rocketmq.store.model.message.PullResult;
import com.automq.rocketmq.store.model.message.PutResult;
import java.util.concurrent.CompletableFuture;

public abstract class TopicQueue {

    protected long topicId;

    protected int queueId;

    protected TopicQueue(long topicId, int queueId) {
        this.topicId = topicId;
        this.queueId = queueId;
    }

    public int getQueueId() {
        return queueId;
    }

    public long getTopicId() {
        return topicId;
    }

    public abstract CompletableFuture<Void> open();

    public abstract CompletableFuture<Void> close();

    public abstract CompletableFuture<PutResult> put(FlatMessage flatMessage);

    public abstract CompletableFuture<PutResult> putRetry(long consumerGroupId, FlatMessage flatMessage);

    public abstract CompletableFuture<PopResult> popNormal(long consumerGroup, Filter filter,
        int batchSize, long invisibleDuration);

    public abstract CompletableFuture<PopResult> popFifo(long consumerGroup, Filter filter,
        int batchSize, long invisibleDuration);

    public abstract CompletableFuture<PopResult> popRetry(long consumerGroup, Filter filter,
        int batchSize, long invisibleDuration);

    public abstract CompletableFuture<AckResult> ack(String receiptHandle);

    public abstract CompletableFuture<Void> ackTimeout(String receiptHandle);

    public abstract CompletableFuture<ChangeInvisibleDurationResult> changeInvisibleDuration(String receiptHandle,
        long invisibleDuration);

    public abstract CompletableFuture<QueueOffsetRange> getOffsetRange();

    public abstract CompletableFuture<Integer> getInflightStats(long consumerGroupId);

    public abstract CompletableFuture<PullResult> pullNormal(long consumerGroupId, Filter filter, long startOffset,
        int batchSize);

    public abstract CompletableFuture<PullResult> pullRetry(long consumerGroupId, Filter filter, long startOffset,
        int batchSize);

    public abstract CompletableFuture<Long> getConsumeOffset(long consumerGroupId);

    public static class QueueOffsetRange {
        private final long startOffset;
        private final long endOffset;

        public QueueOffsetRange(long startOffset, long endOffset) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public long getStartOffset() {
            return startOffset;
        }

        public long getEndOffset() {
            return endOffset;
        }
    }
}
