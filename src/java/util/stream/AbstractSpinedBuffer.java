/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package java.util.stream;

/**
 * Base class for a data structure for gathering elements into a buffer and then
 * iterating them. Maintains an array of increasingly sized arrays, so there is
 * no copying cost associated with growing the data structure.
 * @since 1.8
 */
abstract class AbstractSpinedBuffer {
    /**
     * Minimum power-of-two for the first chunk.
     */
    public static final int MIN_CHUNK_POWER = 4;

    /**
     * Minimum size for the first chunk.
     * 第一个chunk 的最小尺寸
     */
    public static final int MIN_CHUNK_SIZE = 1 << MIN_CHUNK_POWER;

    /**
     * Max power-of-two for chunks.
     */
    public static final int MAX_CHUNK_POWER = 30;

    /**
     * Minimum array size for array-of-chunks.
     */
    public static final int MIN_SPINE_SIZE = 8;


    /**
     * log2 of the size of the first chunk.
     */
    protected final int initialChunkPower;

    /**
     * Index of the *next* element to write; may point into, or just outside of,
     * the current chunk.
     * 当前元素下标
     */
    protected int elementIndex;

    /**
     * Index of the *current* chunk in the spine array, if the spine array is
     * non-null.
     */
    protected int spineIndex;

    /**
     * Count of elements in all prior chunks.
     * 优先插入的 数组
     */
    protected long[] priorElementCount;

    /**
     * Construct with an initial capacity of 16.
     * 脊柱缓冲区 ??? 初始大小为16
     */
    protected AbstractSpinedBuffer() {
        this.initialChunkPower = MIN_CHUNK_POWER;
    }

    /**
     * Construct with a specified initial capacity.
     *
     * @param initialCapacity The minimum expected number of elements
     */
    protected AbstractSpinedBuffer(int initialCapacity) {
        if (initialCapacity < 0)
            throw new IllegalArgumentException("Illegal Capacity: "+ initialCapacity);

        // 初始化大小 该大小代表是2的多少次幂
        this.initialChunkPower = Math.max(MIN_CHUNK_POWER,
                                          Integer.SIZE - Integer.numberOfLeadingZeros(initialCapacity - 1));
    }

    /**
     * Is the buffer currently empty?
     * 脊柱下标和 元素下标都为0的时候 代表为空
     */
    public boolean isEmpty() {
        return (spineIndex == 0) && (elementIndex == 0);
    }

    /**
     * How many elements are currently in the buffer?
     * 脊柱下标为0时 返回元素下标
     */
    public long count() {
        return (spineIndex == 0)
               ? elementIndex
                // 这个priorElementCount 看过去像是 一个个空间对象 可能内部直接维护了 某个偏移量
               : priorElementCount[spineIndex] + elementIndex;
    }

    /**
     * How big should the nth chunk be?
     * n 代表是2的几次  返回 1<<power 就是2的 多少次 代表当前每个chunk的大小
     */
    protected int chunkSize(int n) {
        int power = (n == 0 || n == 1)
                // 0 和1 返回初始值
                    ? initialChunkPower
                    : Math.min(initialChunkPower + n - 1, AbstractSpinedBuffer.MAX_CHUNK_POWER);
        return 1 << power;
    }

    /**
     * Remove all data from the buffer
     * 从 buffer 中移除全部元素
     */
    public abstract void clear();
}
