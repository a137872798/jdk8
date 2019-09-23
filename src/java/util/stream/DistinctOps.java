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

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

/**
 * Factory methods for transforming streams into duplicate-free streams, using
 * {@link Object#equals(Object)} to determine equality.
 *
 * @since 1.8
 * 传入一个 流对象 进行去重操作后返回
 * 该操作属于一个 stateful 动作
 */
final class DistinctOps {

    private DistinctOps() { }

    /**
     * Appends a "distinct" operation to the provided stream, and returns the
     * new stream.
     *
     * @param <T> the type of both input and output elements
     * @param upstream a reference stream with element type T
     * @return the new stream
     * 追加一个去重的操作到上游 并返回新的流
     */
    static <T> ReferencePipeline<T, T> makeRef(AbstractPipeline<?, T, ?> upstream) {
        return new ReferencePipeline.StatefulOp<T, T>(upstream, StreamShape.REFERENCE,
                                                      // 设置特殊的标识
                                                      StreamOpFlag.IS_DISTINCT | StreamOpFlag.NOT_SIZED) {

            /**
             * 传入一个 迭代器 和一个控制流程对象 生成一个 node (该node 中维护了很多元素)
             * @param helper
             * @param spliterator
             * @param <P_IN>
             *
             * @return
             */
            <P_IN> Node<T> reduce(PipelineHelper<T> helper, Spliterator<P_IN> spliterator) {
                // If the stream is SORTED then it should also be ORDERED so the following will also
                // preserve the sort order
                // 生成一个 累加器对象  然后因为 容器是 HashSet 所以自带解决冲突的功能
                TerminalOp<T, LinkedHashSet<T>> reduceOp
                        = ReduceOps.<T, LinkedHashSet<T>>makeRef(LinkedHashSet::new, LinkedHashSet::add,
                                                                 LinkedHashSet::addAll);
                // 将迭代器内的元素 并行处理后 生成节点对象
                return Nodes.node(reduceOp.evaluateParallel(helper, spliterator));
            }

            /**
             * 并行操作实现
             * @param helper
             * @param spliterator
             * @param generator
             * @param <P_IN>
             * @return
             */
            @Override
            <P_IN> Node<T> opEvaluateParallel(PipelineHelper<T> helper,
                                              Spliterator<P_IN> spliterator,
                                              IntFunction<T[]> generator) {
                // 如果本身就已经去重了 不做任何处理  比如 连续调用2次 A.distinct().distinct();
                if (StreamOpFlag.DISTINCT.isKnown(helper.getStreamAndOpFlags())) {
                    // No-op
                    return helper.evaluate(spliterator, false, generator);
                }
                // 如果在之前的操作中已经标识了 Order
                else if (StreamOpFlag.ORDERED.isKnown(helper.getStreamAndOpFlags())) {
                    return reduce(helper, spliterator);
                }
                else {
                    // Holder of null state since ConcurrentHashMap does not support null values
                    // 代表传入了 null 值 因为 ConcurrentHashMap 本身是不支持 以 null 作为key的
                    AtomicBoolean seenNull = new AtomicBoolean(false);
                    ConcurrentHashMap<T, Boolean> map = new ConcurrentHashMap<>();
                    TerminalOp<T, Void> forEachOp = ForEachOps.makeRef(t -> {
                        if (t == null)
                            seenNull.set(true);
                        else
                            map.putIfAbsent(t, Boolean.TRUE);
                    }, false);
                    // 并行执行
                    forEachOp.evaluateParallel(helper, spliterator);

                    // If null has been seen then copy the key set into a HashSet that supports null values
                    // and add null
                    Set<T> keys = map.keySet();
                    // 代表找到了 null
                    if (seenNull.get()) {
                        // TODO Implement a more efficient set-union view, rather than copying
                        keys = new HashSet<>(keys);
                        // 将null 填充回去
                        keys.add(null);
                    }
                    return Nodes.node(keys);
                }
            }

            /**
             * 延迟并行执行
             * @param helper the pipeline helper
             * @param spliterator the source {@code Spliterator}
             * @param <P_IN>
             * @return
             */
            @Override
            <P_IN> Spliterator<T> opEvaluateParallelLazy(PipelineHelper<T> helper, Spliterator<P_IN> spliterator) {
                if (StreamOpFlag.DISTINCT.isKnown(helper.getStreamAndOpFlags())) {
                    // No-op
                    return helper.wrapSpliterator(spliterator);
                }
                else if (StreamOpFlag.ORDERED.isKnown(helper.getStreamAndOpFlags())) {
                    // Not lazy, barrier required to preserve order
                    return reduce(helper, spliterator).spliterator();
                }
                else {
                    // Lazy  wrapSpliterator 内部实现就是将 split 转换成了 一个 supplier 代表延迟调用 (每次只有在调用对应方法时 才通过supplier 获取split 对象)
                    // 保存的元素 会通过 key 来区分
                    return new StreamSpliterators.DistinctSpliterator<>(helper.wrapSpliterator(spliterator));
                }
            }

            /**
             * 包装 Sink 对象  为 整个处理链增加去重的逻辑
             * @param flags The combined stream and operation flags up to, but not
             *        including, this operation
             * @param sink sink to which elements should be sent after processing
             * @return
             */
            @Override
            Sink<T> opWrapSink(int flags, Sink<T> sink) {
                Objects.requireNonNull(sink);

                if (StreamOpFlag.DISTINCT.isKnown(flags)) {
                    return sink;
                    // 如果是有序的 将当前 sink 对象变成有序对象 就是通过在前面添加一层拦截对象 ChainedReference 过滤过元素后发送到下游
                } else if (StreamOpFlag.SORTED.isKnown(flags)) {
                    return new Sink.ChainedReference<T, T>(sink) {

                        /**
                         * 代表本批元素是否有 null
                         */
                        boolean seenNull;
                        /**
                         * 记录最后一个 被消费的元素
                         */
                        T lastSeen;

                        /**
                         * 调用 begin 相当于是状态的初始化
                         * @param size
                         */
                        @Override
                        public void begin(long size) {
                            seenNull = false;
                            lastSeen = null;
                            // 初始化下游容器
                            downstream.begin(-1);
                        }

                        @Override
                        public void end() {
                            seenNull = false;
                            lastSeen = null;
                            downstream.end();
                        }

                        /**
                         * 处理传入的元素
                         * @param t the input argument
                         */
                        @Override
                        public void accept(T t) {
                            if (t == null) {
                                if (!seenNull) {
                                    seenNull = true;
                                    downstream.accept(lastSeen = null);
                                }
                                // 一定要保证 跟上个元素不同 才会被处理  这样只能保证相邻的元素不同 哦 因为 该sink 本身已经满足了 Sorted 所以只要相邻的不同就可以了
                            } else if (lastSeen == null || !t.equals(lastSeen)) {
                                downstream.accept(lastSeen = t);
                            }
                        }
                    };
                } else {
                    /**
                     * 因为该对象是无序的 所以就要存储 所有下发的元素 才能保证不重复
                     */
                    return new Sink.ChainedReference<T, T>(sink) {
                        Set<T> seen;

                        @Override
                        public void begin(long size) {
                            seen = new HashSet<>();
                            downstream.begin(-1);
                        }

                        @Override
                        public void end() {
                            seen = null;
                            downstream.end();
                        }

                        @Override
                        public void accept(T t) {
                            // ！contain 才处理
                            if (!seen.contains(t)) {
                                seen.add(t);
                                downstream.accept(t);
                            }
                        }
                    };
                }
            }
        };
    }
}
