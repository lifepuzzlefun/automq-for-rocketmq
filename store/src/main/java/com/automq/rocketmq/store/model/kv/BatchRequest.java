/*
 * Copyright 2024, AutoMQ CO.,LTD.
 *
 * Use of this software is governed by the Business Source License
 * included in the file BSL.md
 *
 * As of the Change Date specified in that file, in accordance with
 * the Business Source License, use of this software will be governed
 * by the Apache License, Version 2.0
 */

package com.automq.rocketmq.store.model.kv;

public interface BatchRequest {
    /**
     * Required, return the type of the batch request.
     */
    String namespace();

    /**
     * Required, return the key of the kv pair.
     */
    byte[] key();


    /**
     * Optional, return the value of the kv pair.
     */
    default byte[] value() {
        return new byte[] {};
    }


    /**
     * Required, return the type of the batch request.
     */
    BatchRequestType type();
}
