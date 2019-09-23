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

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Spliterator;
import java.util.concurrent.CountedCompleter;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Factory for instances of a short-circuiting {@code TerminalOp} that searches
 * for an element in a stream pipeline, and terminates when it finds one.
 * Supported variants include find-first (find the first element in the
 * encounter order) and find-any (find any element, may not be the first in
 * encounter order.)
 *
 * @since 1.8
 * 有关 查询的 ops
 */
final class FindOps {

    private FindOps() { }

    /**
     * Constructs a {@code TerminalOp} for streams of objects.
     *
     * @param <T> the type of elements of the stream
     * @param mustFindFirst whether the {@code TerminalOp} must produce the
     *        first element in the encounter order
     * @return a {@code TerminalOp} implementing the find operation
     */
    public static <T> TerminalOp<T, Optional<T>> makeRef(boolean mustFindFirst) {
        return new FindOp<>(mustFindFirst, StreamShape.REFERENCE, Optional.empty(),
                            Optional::isPresent, FindSink.OfRef::new);
    }

    /**
     * Constructs a {@code TerminalOp} for streams of ints.
     *
     * @param mustFindFirst whether the {@code TerminalOp} must produce the
     *        first element in the encounter order
     * @return a {@code TerminalOp} implementing the find operation
     */
    public static TerminalOp<Integer, OptionalInt> makeInt(boolean mustFindFirst) {
        return new FindOp<>(mustFindFirst, StreamShape.INT_VALUE, OptionalInt.empty(),
                            OptionalInt::isPresent, FindSink.OfInt::new);
    }

    /**
     * Constructs a {@code TerminalOp} for streams of longs.
     *
     * @param mustFindFirst whether the {@code TerminalOp} must produce the
     *        first element in the encounter order
     * @return a {@code TerminalOp} implementing the find operation
     */
    public static TerminalOp<Long, OptionalLong> makeLong(boolean mustFindFirst) {
        return new FindOp<>(mustFindFirst, StreamShape.LONG_VALUE, OptionalLong.empty(),
                            OptionalLong::isPresent, FindSink.OfLong::new);
    }

    /**
     * Constructs a {@code FindOp} for streams of doubles.
     *
     * @param mustFindFirst whether the {@code TerminalOp} must produce the
     *        first element in the encounter order
     * @return a {@code TerminalOp} implementing the find operation
     */
    public static TerminalOp<Double, OptionalDouble> makeDouble(boolean mustFindFirst) {
        return new FindOp<>(mustFindFirst, StreamShape.DOUBLE_VALUE, OptionalDouble.empty(),
                            OptionalDouble::isPresent, FindSink.OfDouble::new);
    }

    /**
     * A short-circuiting {@code TerminalOp} that searches for an element in a
     * stream pipeline, and terminates when it finds one.  Implements both
     * find-first (find the first element in the encounter order) and find-any
     * (find any element, may not be the first in encounter order.)
     *
     * @param <T> the output type of the stream pipeline
     * @param <O> the result type of the find operation, typically an optional
     *        type
     *           查询的 操作对象
     */
    private static final class FindOp<T, O> implements TerminalOp<T, O> {

        /**
         * 流的类型
         */
        private final StreamShape shape;
        /**
         * 是否需要查询第一个元素
         */
        final boolean mustFindFirst;
        /**
         * 返回的空元素 对象 因为find 可能会返回 空对象
         */
        final O emptyValue;
        /**
         * 判断是否为空的谓语对象
         */
        final Predicate<O> presentPredicate;
        /**
         * 构建 sink 的 提供者
         */
        final Supplier<TerminalSink<T, O>> sinkSupplier;

        /**
         * Constructs a {@code FindOp}.
         *
         * @param mustFindFirst if true, must find the first element in
         *        encounter order, otherwise can find any element
         * @param shape stream shape of elements to search
         * @param emptyValue result value corresponding to "found nothing"
         * @param presentPredicate {@code Predicate} on result value
         *        corresponding to "found something"
         * @param sinkSupplier supplier for a {@code TerminalSink} implementing
         *        the matching functionality
         */
        FindOp(boolean mustFindFirst,
                       StreamShape shape,
                       // 代表默认的空值对象
                       O emptyValue,
                       Predicate<O> presentPredicate,
                       Supplier<TerminalSink<T, O>> sinkSupplier) {
            this.mustFindFirst = mustFindFirst;
            this.shape = shape;
            this.emptyValue = emptyValue;
            this.presentPredicate = presentPredicate;
            this.sinkSupplier = sinkSupplier;
        }

        /**
         * 默认是 短路
         * @return
         */
        @Override
        public int getOpFlags() {
            // 如果没有设置 mustFindFirst 代表是无序的
            return StreamOpFlag.IS_SHORT_CIRCUIT | (mustFindFirst ? 0 : StreamOpFlag.NOT_ORDERED);
        }

        @Override
        public StreamShape inputShape() {
            return shape;
        }

        @Override
        public <S> O evaluateSequential(PipelineHelper<T> helper,
                                        Spliterator<S> spliterator) {
            // 将迭代器的数据填充到 sink 中
            O result = helper.wrapAndCopyInto(sinkSupplier.get(), spliterator).get();
            return result != null ? result : emptyValue;
        }

        /**
         * 并行执行都是通过返回一个 task 对象
         * @param helper the pipeline helper
         * @param spliterator the source spliterator
         * @param <P_IN>
         * @return
         */
        @Override
        public <P_IN> O evaluateParallel(PipelineHelper<T> helper,
                                         Spliterator<P_IN> spliterator) {
            return new FindTask<>(this, helper, spliterator).invoke();
        }
    }

    /**
     * Implementation of @{code TerminalSink} that implements the find
     * functionality, requesting cancellation when something has been found
     *
     * @param <T> The type of input element
     * @param <O> The result type, typically an optional type
     *           具备 find 功能的 下沉对象
     */
    private static abstract class FindSink<T, O> implements TerminalSink<T, O> {

        /**
         * 代表是否设置了 数值
         */
        boolean hasValue;
        /**
         * 存储对应的值
         */
        T value;

        FindSink() {} // Avoid creation of special accessor

        /**
         * source 中每个元素 都经过整个sink 链到达底部 只要有首个进入到底部 设置 hasValue == true 实现短路
         * @param value
         */
        @Override
        public void accept(T value) {
            if (!hasValue) {
                hasValue = true;
                this.value = value;
            }
        }

        /**
         * 一旦有数据 就不再需要接受其他数据
         * @return
         */
        @Override
        public boolean cancellationRequested() {
            return hasValue;
        }

        /** Specialization of {@code FindSink} for reference streams */
        static final class OfRef<T> extends FindSink<T, Optional<T>> {
            @Override
            public Optional<T> get() {
                return hasValue ? Optional.of(value) : null;
            }
        }

        /** Specialization of {@code FindSink} for int streams */
        static final class OfInt extends FindSink<Integer, OptionalInt>
                implements Sink.OfInt {
            @Override
            public void accept(int value) {
                // Boxing is OK here, since few values will actually flow into the sink
                accept((Integer) value);
            }

            @Override
            public OptionalInt get() {
                return hasValue ? OptionalInt.of(value) : null;
            }
        }

        /** Specialization of {@code FindSink} for long streams */
        static final class OfLong extends FindSink<Long, OptionalLong>
                implements Sink.OfLong {
            @Override
            public void accept(long value) {
                // Boxing is OK here, since few values will actually flow into the sink
                accept((Long) value);
            }

            @Override
            public OptionalLong get() {
                return hasValue ? OptionalLong.of(value) : null;
            }
        }

        /** Specialization of {@code FindSink} for double streams */
        static final class OfDouble extends FindSink<Double, OptionalDouble>
                implements Sink.OfDouble {
            @Override
            public void accept(double value) {
                // Boxing is OK here, since few values will actually flow into the sink
                accept((Double) value);
            }

            @Override
            public OptionalDouble get() {
                return hasValue ? OptionalDouble.of(value) : null;
            }
        }
    }

    /**
     * {@code ForkJoinTask} implementing parallel short-circuiting search
     * @param <P_IN> Input element type to the stream pipeline
     * @param <P_OUT> Output element type from the stream pipeline
     * @param <O> Result type from the find operation
     *           并行任务对象
     */
    @SuppressWarnings("serial")
    private static final class FindTask<P_IN, P_OUT, O>
            extends AbstractShortCircuitTask<P_IN, P_OUT, O, FindTask<P_IN, P_OUT, O>> {
        private final FindOp<P_OUT, O> op;

        FindTask(FindOp<P_OUT, O> op,
                 PipelineHelper<P_OUT> helper,
                 Spliterator<P_IN> spliterator) {
            super(helper, spliterator);
            this.op = op;
        }

        FindTask(FindTask<P_IN, P_OUT, O> parent, Spliterator<P_IN> spliterator) {
            super(parent, spliterator);
            this.op = parent.op;
        }

        @Override
        protected FindTask<P_IN, P_OUT, O> makeChild(Spliterator<P_IN> spliterator) {
            return new FindTask<>(this, spliterator);
        }

        @Override
        protected O getEmptyResult() {
            return op.emptyValue;
        }

        /**
         * 代表找到了结果
         * @param answer
         */
        private void foundResult(O answer) {
            // 如果是 最左节点  触发短路函数
            if (isLeftmostNode())
                shortCircuit(answer);
            else
                // 不是最左节点的情况下
                cancelLaterNodes();
        }

        @Override
        protected O doLeaf() {
            // 将迭代器中的数据 转移到生成的 sink 对象中   因为sink 内部有一个consumer函数 会对传入的数据进行过滤 并在内部生成结果对象 之后调用get() 获取结果
            O result = helper.wrapAndCopyInto(op.sinkSupplier.get(), spliterator).get();
            if (!op.mustFindFirst) {
                if (result != null)
                    // 触发短路函数 就是设置一个 shardingResult
                    shortCircuit(result);
                return null;
            }
            else {
                if (result != null) {
                    // 在 只要求 findFirst 的情况 直接将结果设置就好
                    foundResult(result);
                    return result;
                }
                else
                    return null;
            }
        }

        /**
         * 当任务完成时
         * @param caller
         */
        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            if (op.mustFindFirst) {
                    for (FindTask<P_IN, P_OUT, O> child = leftChild, p = null; child != p; p = child, child = rightChild) {
                    O result = child.getLocalResult();
                    // 存在结果 且 判定结果是否有效的谓语返回为 true
                    if (result != null && op.presentPredicate.test(result)) {
                        setLocalResult(result);
                        foundResult(result);
                        break;
                    }
                }
            }
            // 清理工作
            super.onCompletion(caller);
        }
    }
}

