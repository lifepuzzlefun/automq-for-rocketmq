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

package com.automq.rocketmq.store;

import com.automq.rocketmq.store.api.MessageStateMachine;
import com.automq.rocketmq.store.exception.StoreErrorCode;
import com.automq.rocketmq.store.exception.StoreException;
import com.automq.rocketmq.store.model.generated.CheckPoint;
import com.automq.rocketmq.store.model.kv.BatchDeleteRequest;
import com.automq.rocketmq.store.model.kv.BatchRequest;
import com.automq.rocketmq.store.model.kv.BatchWriteRequest;
import com.automq.rocketmq.store.model.metadata.ConsumerGroupMetadata;
import com.automq.rocketmq.store.model.operation.AckOperation;
import com.automq.rocketmq.store.model.operation.ChangeInvisibleDurationOperation;
import com.automq.rocketmq.store.model.operation.Operation;
import com.automq.rocketmq.store.model.operation.OperationSnapshot;
import com.automq.rocketmq.store.model.operation.PopOperation;
import com.automq.rocketmq.store.service.api.KVService;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static com.automq.rocketmq.store.MessageStoreImpl.KV_NAMESPACE_CHECK_POINT;
import static com.automq.rocketmq.store.MessageStoreImpl.KV_NAMESPACE_FIFO_INDEX;
import static com.automq.rocketmq.store.MessageStoreImpl.KV_NAMESPACE_TIMER_TAG;
import static com.automq.rocketmq.store.util.SerializeUtil.buildCheckPointKey;
import static com.automq.rocketmq.store.util.SerializeUtil.buildCheckPointValue;
import static com.automq.rocketmq.store.util.SerializeUtil.buildOrderIndexKey;
import static com.automq.rocketmq.store.util.SerializeUtil.buildOrderIndexValue;
import static com.automq.rocketmq.store.util.SerializeUtil.buildReceiptHandle;
import static com.automq.rocketmq.store.util.SerializeUtil.buildTimerTagKey;

public class DefaultMessageStateMachine implements MessageStateMachine {
    // TODO: concurrent protection
    private final long topicId;
    private final int queueId;
    private Map<Long/*consumerGroup*/, ConsumerGroupMetadata> consumerGroupMetadataMap;
    private Map<Long/*consumerGroup*/, AckCommitter> ackCommitterMap = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    private final KVService kvService;

    public DefaultMessageStateMachine(long topicId, int queueId, KVService kvService) {
        this.consumerGroupMetadataMap = new ConcurrentHashMap<>();
        this.kvService = kvService;
        this.topicId = topicId;
        this.queueId = queueId;
    }

    @Override
    public CompletableFuture<Void> replayPopOperation(PopOperation operation) {
        long topicId = operation.getTopicId();
        int queueId = operation.getQueueId();
        long offset = operation.getOffset();
        long consumerGroupId = operation.getConsumerGroupId();
        long operationId = operation.getOperationTimestamp();
        long operationTimestamp = operation.getOperationTimestamp();
        long nextVisibleTimestamp = operation.getOperationTimestamp() + operation.getInvisibleDuration();
        int count = operation.getCount();
        boolean fifo = operation.getPopOperationType() == PopOperation.PopOperationType.POP_ORDER;
        boolean retry = operation.getPopOperationType() == PopOperation.PopOperationType.POP_RETRY;
        writeLock.lock();
        try {
            // update consume offset
            ConsumerGroupMetadata metadata = this.consumerGroupMetadataMap.computeIfAbsent(consumerGroupId, k -> new ConsumerGroupMetadata(consumerGroupId));
            if (metadata.getConsumeOffset() < offset + 1) {
                metadata.setConsumeOffset(offset + 1);
            }
            if (operation.getPopOperationType() == PopOperation.PopOperationType.POP_LAST) {
                // TODO: handle this case with a separate operation type, now just handle it here
                // if this is a pop-last operation, it only needs to update consume offset and advance ack offset
                long baseOffset = offset - count + 1;
                for (int i = 0; i < count; i++) {
                    long currOffset = baseOffset + i;
                    this.ackCommitterMap.computeIfAbsent(consumerGroupId, k -> new AckCommitter(consumerGroupId, metadata.getAckOffset())).commitAck(currOffset);
                }
                return CompletableFuture.completedFuture(null);
            }
            // add consume times
            int consumeTimes = metadata.getConsumeTimesMap().getOrDefault(offset, 0) + 1;
            metadata.getConsumeTimesMap().put(offset, consumeTimes);

            List<BatchRequest> requestList = new ArrayList<>();
            // write a ck for this offset
            BatchWriteRequest writeCheckPointRequest = new BatchWriteRequest(KV_NAMESPACE_CHECK_POINT,
                buildCheckPointKey(topicId, queueId, offset, operationId),
                buildCheckPointValue(topicId, queueId, offset,
                    count,
                    consumerGroupId, operationId, fifo, retry, operationTimestamp, nextVisibleTimestamp));
            requestList.add(writeCheckPointRequest);

            BatchWriteRequest writeTimerTagRequest = new BatchWriteRequest(KV_NAMESPACE_TIMER_TAG,
                buildTimerTagKey(nextVisibleTimestamp, topicId, queueId, offset, operationId),
                buildReceiptHandle(consumerGroupId, topicId, queueId, offset, operationId));
            requestList.add(writeTimerTagRequest);

            // if this message is orderly, write order index for each offset in this operation to KV service
            if (fifo) {
                long baseOffset = offset - count + 1;
                for (int i = 0; i < count; i++) {
                    long currOffset = baseOffset + i;
                    BatchWriteRequest writeOrderIndexRequest = new BatchWriteRequest(KV_NAMESPACE_FIFO_INDEX,
                        buildOrderIndexKey(consumerGroupId, topicId, queueId, currOffset), buildOrderIndexValue(operationId));
                    requestList.add(writeOrderIndexRequest);
                }
            }

            // if this message is retry, advance retry offset
            if (retry) {
                // advance offset of retry stream
                long currRetryOffset = metadata.getRetryOffset();
                long newRetryOffset = operation.getRetryOffset();
                if (newRetryOffset + 1 > currRetryOffset) {
                    metadata.setRetryOffset(newRetryOffset + 1);
                }
            }

            kvService.batch(requestList.toArray(new BatchRequest[0]));
        } catch (StoreException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            writeLock.unlock();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> replayAckOperation(AckOperation operation) {
        long topicId = operation.getTopicId();
        int queueId = operation.getQueueId();
        long offset = operation.getOffset();
        long operationId = operation.getOperationId();
        AckOperation.AckOperationType type = operation.getAckOperationType();
        writeLock.lock();
        try {
            // check if ck exists
            byte[] ckKey = buildCheckPointKey(topicId, queueId, offset, operationId);
            byte[] ckValue = kvService.get(KV_NAMESPACE_CHECK_POINT, ckKey);
            if (ckValue == null) {
                throw new StoreException(StoreErrorCode.ILLEGAL_ARGUMENT, "Ack operation failed, check point not found");
            }
            CheckPoint ck = CheckPoint.getRootAsCheckPoint(ByteBuffer.wrap(ckValue));
            List<BatchRequest> requestList = new ArrayList<>();
            int count = ck.count();

            // delete ck, timer tag
            BatchDeleteRequest deleteCheckPointRequest = new BatchDeleteRequest(KV_NAMESPACE_CHECK_POINT, ckKey);
            requestList.add(deleteCheckPointRequest);

            BatchDeleteRequest deleteTimerTagRequest = new BatchDeleteRequest(KV_NAMESPACE_TIMER_TAG,
                buildTimerTagKey(ck.nextVisibleTimestamp(), topicId, queueId, offset, operationId));
            requestList.add(deleteTimerTagRequest);

            long baseOffset = offset - count + 1;
            for (int i = 0; i < count; i++) {
                long currOffset = baseOffset + i;
                // delete order index
                if (ck.fifo()) {
                    BatchDeleteRequest deleteOrderIndexRequest = new BatchDeleteRequest(KV_NAMESPACE_FIFO_INDEX,
                        buildOrderIndexKey(ck.consumerGroupId(), topicId, queueId, offset));
                    requestList.add(deleteOrderIndexRequest);
                }
                // advance ack offset
                if (type == AckOperation.AckOperationType.ACK_NORMAL || !ck.fifo()) {
                    // only advance ack offset when ack-normal or ack-timeout but not fifo
                    long consumerGroupId = ck.consumerGroupId();
                    ConsumerGroupMetadata metadata = this.consumerGroupMetadataMap.computeIfAbsent(consumerGroupId, k -> new ConsumerGroupMetadata(consumerGroupId));
                    this.ackCommitterMap.computeIfAbsent(ck.consumerGroupId(), k -> new AckCommitter(consumerGroupId, metadata.getAckOffset())).commitAck(currOffset);
                }
            }

            kvService.batch(requestList.toArray(new BatchRequest[0]));
        } catch (StoreException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            writeLock.unlock();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> replayChangeInvisibleDurationOperation(ChangeInvisibleDurationOperation operation) {
        long invisibleDuration = operation.getInvisibleDuration();
        long operationTimestamp = operation.getOperationTimestamp();
        long topic = operation.getTopicId();
        int queue = operation.getQueueId();
        long offset = operation.getOffset();
        long operationId = operation.getOperationId();
        long nextInvisibleTimestamp = operationTimestamp + invisibleDuration;
        writeLock.lock();
        try {
            // Check if check point exists.
            byte[] checkPointKey = buildCheckPointKey(topic, queue, offset, operationId);
            byte[] buffer = kvService.get(KV_NAMESPACE_CHECK_POINT, checkPointKey);
            if (buffer == null) {
                throw new StoreException(StoreErrorCode.ILLEGAL_ARGUMENT, "Change invisible duration operation failed, check point not found");
            }
            // Delete last timer tag.
            CheckPoint checkPoint = CheckPoint.getRootAsCheckPoint(ByteBuffer.wrap(buffer));
            BatchDeleteRequest deleteLastTimerTagRequest = new BatchDeleteRequest(KV_NAMESPACE_TIMER_TAG,
                buildTimerTagKey(checkPoint.nextVisibleTimestamp(), checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.operationId()));

            // Write new check point and timer tag.
            BatchWriteRequest writeCheckPointRequest = new BatchWriteRequest(KV_NAMESPACE_CHECK_POINT,
                buildCheckPointKey(checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.operationId()),
                buildCheckPointValue(checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.count(),
                    checkPoint.consumerGroupId(), checkPoint.operationId(), checkPoint.fifo(), checkPoint.retry(),
                    checkPoint.deliveryTimestamp(), nextInvisibleTimestamp));

            BatchWriteRequest writeTimerTagRequest = new BatchWriteRequest(KV_NAMESPACE_TIMER_TAG,
                buildTimerTagKey(nextInvisibleTimestamp, checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.operationId()),
                buildReceiptHandle(checkPoint.consumerGroupId(), checkPoint.topicId(), checkPoint.queueId(), checkPoint.messageOffset(), checkPoint.operationId()));
            kvService.batch(deleteLastTimerTagRequest, writeCheckPointRequest, writeTimerTagRequest);
        } catch (StoreException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            writeLock.unlock();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<OperationSnapshot> takeSnapshot() {

        return null;
    }

    @Override
    public CompletableFuture<Void> loadSnapshot(OperationSnapshot snapshot) {
        writeLock.lock();
        try {
            this.consumerGroupMetadataMap = snapshot.getConsumerGroupMetadataList().stream().collect(Collectors.toMap(
                ConsumerGroupMetadata::getConsumerGroupId, metadata -> metadata));
            List<CompletableFuture<Void>> replayAllCfs = snapshot.getPopOperations().stream().map(this::replayPopOperation).collect(Collectors.toList());
            return CompletableFuture.allOf(replayAllCfs.toArray(new CompletableFuture[0]));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public CompletableFuture<List<Operation>> revive() {
        return null;
    }

    @Override
    public CompletableFuture<Long> consumeOffset(long consumerGroupId) {
        return CompletableFuture.completedFuture(consumerGroupMetadataMap.computeIfAbsent(consumerGroupId, k -> new ConsumerGroupMetadata(consumerGroupId)).getConsumeOffset());
    }

    @Override
    public CompletableFuture<Long> ackOffset(long consumerGroupId) {
        return CompletableFuture.completedFuture(consumerGroupMetadataMap.computeIfAbsent(consumerGroupId, k -> new ConsumerGroupMetadata(consumerGroupId)).getAckOffset());
    }

    @Override
    public CompletableFuture<Long> retryOffset(long consumerGroupId) {
        return CompletableFuture.completedFuture(consumerGroupMetadataMap.computeIfAbsent(consumerGroupId, k -> new ConsumerGroupMetadata(consumerGroupId)).getRetryOffset());
    }

    @Override
    public CompletableFuture<Boolean> isLocked(long consumerGroupId, long offset) {
        readLock.lock();
        try {
            byte[] lockKey = buildOrderIndexKey(consumerGroupId, topicId, queueId, offset);
            return CompletableFuture.completedFuture(kvService.get(KV_NAMESPACE_FIFO_INDEX, lockKey) != null);
        } catch (StoreException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            readLock.unlock();
        }
    }

    class AckCommitter {
        public static final int BIT_SET_SIZE = 1024 * 8; // 1KB
        public static final float BIT_SET_LOAD_FACTOR = 0.75f;
        private final long consumerGroupId;
        private long ackOffset;
        private long baseOffset;
        private BitSet bitSet;

        public AckCommitter(long consumerGroupId, long ackOffset) {
            this.consumerGroupId = consumerGroupId;
            this.ackOffset = ackOffset;
            this.baseOffset = ackOffset;
            this.bitSet = new BitSet(BIT_SET_SIZE);
        }

        public void commitAck(long offset) {
            if (offset >= ackOffset) {
                bitSet.set((int) (offset - baseOffset));
                boolean advance = false;
                while (bitSet.get((int) (ackOffset - baseOffset))) {
                    ackOffset++;
                    advance = true;
                }
                if (advance) {
                    DefaultMessageStateMachine.this.consumerGroupMetadataMap
                        .computeIfAbsent(consumerGroupId, k -> new ConsumerGroupMetadata(consumerGroupId))
                        .setAckOffset(ackOffset);
                    if (ackOffset - baseOffset >= BIT_SET_SIZE * BIT_SET_LOAD_FACTOR) {
                        rollingBitSet();
                    }
                }
            }
        }

        private void rollingBitSet() {
            BitSet newBitSet = new BitSet(BIT_SET_SIZE);
            long newBaseOffset = ackOffset;
            for (int i = (int) (ackOffset - baseOffset); i < BIT_SET_SIZE; i++) {
                newBitSet.set(i - (int) (ackOffset - baseOffset), bitSet.get(i));
            }
            this.bitSet = newBitSet;
            this.baseOffset = newBaseOffset;
        }
    }
}
