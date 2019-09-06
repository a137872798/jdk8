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

import java.util.Comparator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Spliterator implementations for wrapping and delegating spliterators, used
 * in the implementation of the {@link Stream#spliterator()} method.
 *
 * @since 1.8
 * 基于流的 拆分迭代器
 */
class StreamSpliterators {

    /**
     * Abstract wrapping spliterator that binds to the spliterator of a
     * pipeline helper on first operation.
     *
     * <p>This spliterator is not late-binding and will bind to the source
     * spliterator when first operated on.
     *
     * <p>A wrapping spliterator produced from a sequential stream
     * cannot be split if there are stateful operations present.
     * 实现了 拆分迭代器接口 并且内部容器使用了 脊柱buffer 就是一个每下个数组大小都是原来2倍的 扩容数组
     */
    private static abstract class AbstractWrappingSpliterator<P_IN, P_OUT,
                                                              T_BUFFER extends AbstractSpinedBuffer>
            implements Spliterator<P_OUT> {

        // @@@ Detect if stateful operations are present or not
        //     If not then can split otherwise cannot

        /**
         * True if this spliterator supports splitting
         * 判断本迭代器是否支持并行
         */
        final boolean isParallel;

        /**
         * 该接口实现了 逻辑的定义
         */
        final PipelineHelper<P_OUT> ph;

        /**
         * Supplier for the source spliterator.  Client provides either a
         * spliterator or a supplier.
         * 生成迭代器的对象 spliteratorSupplier / spliterator 2选1
         */
        private Supplier<Spliterator<P_IN>> spliteratorSupplier;

        /**
         * Source spliterator.  Either provided from client or obtained from
         * supplier.
         */
        Spliterator<P_IN> spliterator;

        /**
         * Sink chain for the downstream stages of the pipeline, ultimately
         * leading to the buffer. Used during partial traversal
         * 该对象 具备 填充元素的能力
         */
        Sink<P_IN> bufferSink;

        /**
         * A function that advances one element of the spliterator, pushing
         * it to bufferSink.  Returns whether any elements were processed.
         * Used during partial traversal.
         * 判断元素是否满足 push条件
         */
        BooleanSupplier pusher;

        /** Next element to consume from the buffer, used during partial traversal */
        /**
         * 下个元素 有多少消费者???
         */
        long nextToConsume;

        /** Buffer into which elements are pushed.  Used during partial traversal. */
        /**
         * 目标容器
         */
        T_BUFFER buffer;

        /**
         * True if full traversal has occurred (with possible cancelation).
         * If doing a partial traversal, there may be still elements in buffer.
         * 判断是否全部元素都已经遍历
         */
        boolean finished;

        /**
         * Construct an AbstractWrappingSpliterator from a
         * {@code Supplier<Spliterator>}.
         */
        AbstractWrappingSpliterator(PipelineHelper<P_OUT> ph,
                                    Supplier<Spliterator<P_IN>> spliteratorSupplier,
                                    boolean parallel) {
            this.ph = ph;
            this.spliteratorSupplier = spliteratorSupplier;
            this.spliterator = null;
            this.isParallel = parallel;
        }

        /**
         * Construct an AbstractWrappingSpliterator from a
         * {@code Spliterator}.
         */
        AbstractWrappingSpliterator(PipelineHelper<P_OUT> ph,
                                    Spliterator<P_IN> spliterator,
                                    boolean parallel) {
            this.ph = ph;
            this.spliteratorSupplier = null;
            this.spliterator = spliterator;
            this.isParallel = parallel;
        }

        /**
         * Called before advancing to set up spliterator, if needed.
         * 初始化 split迭代器对象
         */
        final void init() {
            if (spliterator == null) {
                spliterator = spliteratorSupplier.get();
                spliteratorSupplier = null;
            }
        }

        /**
         * Get an element from the source, pushing it into the sink chain,
         * setting up the buffer if needed
         * @return whether there are elements to consume from the buffer
         * 获取下个元素
         */
        final boolean doAdvance() {
            // 容器未初始化 有2种情况 一种是 已经处理完了 一种是 还没有进行初始化
            if (buffer == null) {
                if (finished)
                    return false;

                init();
                // 初始化 buffer 以及相关函数
                initPartialTraversalState();
                nextToConsume = 0;
                // 为buffer 开辟指定的大小
                bufferSink.begin(spliterator.getExactSizeIfKnown());
                // 每调用一次 fillBuffer 就会将迭代器的元素转移到 buffer 中
                return fillBuffer();
            }
            else {
                // 初始化成功后 每次 移动 偏移量
                ++nextToConsume;
                boolean hasNext = nextToConsume < buffer.count();
                // 这里 重新填充了???
                if (!hasNext) {
                    nextToConsume = 0;
                    buffer.clear();
                    hasNext = fillBuffer();
                }
                return hasNext;
            }
        }

        /**
         * Invokes the shape-specific constructor with the provided arguments
         * and returns the result.
         */
        abstract AbstractWrappingSpliterator<P_IN, P_OUT, ?> wrap(Spliterator<P_IN> s);

        /**
         * Initializes buffer, sink chain, and pusher for a shape-specific
         * implementation.
         * 初始化相关容器
         */
        abstract void initPartialTraversalState();

        /**
         * 分裂产生新的迭代器
         * @return
         */
        @Override
        public Spliterator<P_OUT> trySplit() {
            // 必须要支持并行
            if (isParallel && !finished) {
                init();

                Spliterator<P_IN> split = spliterator.trySplit();
                // 包装返回的迭代器
                return (split == null) ? null : wrap(split);
            }
            else
                return null;
        }

        /**
         * If the buffer is empty, push elements into the sink chain until
         * the source is empty or cancellation is requested.
         * @return whether there are elements to consume from the buffer
         * 填充容器
         */
        private boolean fillBuffer() {
            // 要求 buffer.count 必须是0
            while (buffer.count() == 0) {
                // 如果不期望接收其他元素 或者 push 失败  再判断中已经触发了 一次 push操作 所以下次会跳出循环
                if (bufferSink.cancellationRequested() || !pusher.getAsBoolean()) {
                    if (finished)
                        return false;
                    else {
                        bufferSink.end(); // might trigger more elements
                        finished = true;
                    }
                }
            }
            return true;
        }

        /**
         * 评估大小
         * @return
         */
        @Override
        public final long estimateSize() {
            init();
            // Use the estimate of the wrapped spliterator
            // Note this may not be accurate if there are filter/flatMap
            // operations filtering or adding elements to the stream
            return spliterator.estimateSize();
        }

        /**
         * 返回大小信息
         * @return
         */
        @Override
        public final long getExactSizeIfKnown() {
            init();
            return StreamOpFlag.SIZED.isKnown(ph.getStreamAndOpFlags())
                   ? spliterator.getExactSizeIfKnown()
                   : -1;
        }

        @Override
        public final int characteristics() {
            init();

            // Get the characteristics from the pipeline
            int c = StreamOpFlag.toCharacteristics(StreamOpFlag.toStreamFlags(ph.getStreamAndOpFlags()));

            // Mask off the size and uniform characteristics and replace with
            // those of the spliterator
            // Note that a non-uniform spliterator can change from something
            // with an exact size to an estimate for a sub-split, for example
            // with HashSet where the size is known at the top level spliterator
            // but for sub-splits only an estimate is known
            if ((c & Spliterator.SIZED) != 0) {
                c &= ~(Spliterator.SIZED | Spliterator.SUBSIZED);
                c |= (spliterator.characteristics() & (Spliterator.SIZED | Spliterator.SUBSIZED));
            }

            return c;
        }

        /**
         * 设置了 SORTED 特性才代表支持 返回 Comparator 且一般情况下是返回null
         * @return
         */
        @Override
        public Comparator<? super P_OUT> getComparator() {
            if (!hasCharacteristics(SORTED))
                throw new IllegalStateException();
            return null;
        }

        @Override
        public final String toString() {
            return String.format("%s[%s]", getClass().getName(), spliterator);
        }
    }

    /**
     * 默认实现类
     * @param <P_IN>
     * @param <P_OUT>
     */
    static final class WrappingSpliterator<P_IN, P_OUT>
            extends AbstractWrappingSpliterator<P_IN, P_OUT, SpinedBuffer<P_OUT>> {

        WrappingSpliterator(PipelineHelper<P_OUT> ph,
                            Supplier<Spliterator<P_IN>> supplier,
                            boolean parallel) {
            super(ph, supplier, parallel);
        }

        WrappingSpliterator(PipelineHelper<P_OUT> ph,
                            Spliterator<P_IN> spliterator,
                            boolean parallel) {
            super(ph, spliterator, parallel);
        }

        /**
         * 将传入的迭代器对象包装成该对象
         * @param s
         * @return
         */
        @Override
        WrappingSpliterator<P_IN, P_OUT> wrap(Spliterator<P_IN> s) {
            return new WrappingSpliterator<>(ph, s, isParallel);
        }

        @Override
        void initPartialTraversalState() {
            // 创建一个 脊柱buffer
            SpinedBuffer<P_OUT> b = new SpinedBuffer<>();
            buffer = b;
            // 将 buffer 包装成 sink 对象
            bufferSink = ph.wrapSink(b::accept);
            // pusher 代表 每次 执行 tryAdvance 是否成功
            // tryAdvance 会从迭代器中取出满足 buffer 数量的元素 并执行消费者函数  针对 spinedBuffer 就是 添加到容器中
            pusher = () -> spliterator.tryAdvance(bufferSink);
        }

        /**
         * 尝试获取下个元素
         * @param consumer
         * @return
         */
        @Override
        public boolean tryAdvance(Consumer<? super P_OUT> consumer) {
            Objects.requireNonNull(consumer);
            boolean hasNext = doAdvance();
            if (hasNext)
                // 使用消费者 处理获取到的元素
                consumer.accept(buffer.get(nextToConsume));
            return hasNext;
        }

        @Override
        public void forEachRemaining(Consumer<? super P_OUT> consumer) {
            // buffer == null 代表元素还没初始化
            if (buffer == null && !finished) {
                Objects.requireNonNull(consumer);
                init();

                // 这里 强转了 consumer 变成sink 后 包装
                ph.wrapAndCopyInto((Sink<P_OUT>) consumer::accept, spliterator);
                finished = true;
            }
            else {
                // 遍历所有元素进行消费
                do { } while (tryAdvance(consumer));
            }
        }
    }

    static final class IntWrappingSpliterator<P_IN>
            extends AbstractWrappingSpliterator<P_IN, Integer, SpinedBuffer.OfInt>
            implements Spliterator.OfInt {

        IntWrappingSpliterator(PipelineHelper<Integer> ph,
                               Supplier<Spliterator<P_IN>> supplier,
                               boolean parallel) {
            super(ph, supplier, parallel);
        }

        IntWrappingSpliterator(PipelineHelper<Integer> ph,
                               Spliterator<P_IN> spliterator,
                               boolean parallel) {
            super(ph, spliterator, parallel);
        }

        @Override
        AbstractWrappingSpliterator<P_IN, Integer, ?> wrap(Spliterator<P_IN> s) {
            return new IntWrappingSpliterator<>(ph, s, isParallel);
        }

        @Override
        void initPartialTraversalState() {
            SpinedBuffer.OfInt b = new SpinedBuffer.OfInt();
            buffer = b;
            bufferSink = ph.wrapSink((Sink.OfInt) b::accept);
            pusher = () -> spliterator.tryAdvance(bufferSink);
        }

        @Override
        public Spliterator.OfInt trySplit() {
            return (Spliterator.OfInt) super.trySplit();
        }

        @Override
        public boolean tryAdvance(IntConsumer consumer) {
            Objects.requireNonNull(consumer);
            boolean hasNext = doAdvance();
            if (hasNext)
                consumer.accept(buffer.get(nextToConsume));
            return hasNext;
        }

        @Override
        public void forEachRemaining(IntConsumer consumer) {
            if (buffer == null && !finished) {
                Objects.requireNonNull(consumer);
                init();

                ph.wrapAndCopyInto((Sink.OfInt) consumer::accept, spliterator);
                finished = true;
            }
            else {
                do { } while (tryAdvance(consumer));
            }
        }
    }

    static final class LongWrappingSpliterator<P_IN>
            extends AbstractWrappingSpliterator<P_IN, Long, SpinedBuffer.OfLong>
            implements Spliterator.OfLong {

        LongWrappingSpliterator(PipelineHelper<Long> ph,
                                Supplier<Spliterator<P_IN>> supplier,
                                boolean parallel) {
            super(ph, supplier, parallel);
        }

        LongWrappingSpliterator(PipelineHelper<Long> ph,
                                Spliterator<P_IN> spliterator,
                                boolean parallel) {
            super(ph, spliterator, parallel);
        }

        @Override
        AbstractWrappingSpliterator<P_IN, Long, ?> wrap(Spliterator<P_IN> s) {
            return new LongWrappingSpliterator<>(ph, s, isParallel);
        }

        @Override
        void initPartialTraversalState() {
            SpinedBuffer.OfLong b = new SpinedBuffer.OfLong();
            buffer = b;
            bufferSink = ph.wrapSink((Sink.OfLong) b::accept);
            pusher = () -> spliterator.tryAdvance(bufferSink);
        }

        @Override
        public Spliterator.OfLong trySplit() {
            return (Spliterator.OfLong) super.trySplit();
        }

        @Override
        public boolean tryAdvance(LongConsumer consumer) {
            Objects.requireNonNull(consumer);
            boolean hasNext = doAdvance();
            if (hasNext)
                consumer.accept(buffer.get(nextToConsume));
            return hasNext;
        }

        @Override
        public void forEachRemaining(LongConsumer consumer) {
            if (buffer == null && !finished) {
                Objects.requireNonNull(consumer);
                init();

                ph.wrapAndCopyInto((Sink.OfLong) consumer::accept, spliterator);
                finished = true;
            }
            else {
                do { } while (tryAdvance(consumer));
            }
        }
    }

    static final class DoubleWrappingSpliterator<P_IN>
            extends AbstractWrappingSpliterator<P_IN, Double, SpinedBuffer.OfDouble>
            implements Spliterator.OfDouble {

        DoubleWrappingSpliterator(PipelineHelper<Double> ph,
                                  Supplier<Spliterator<P_IN>> supplier,
                                  boolean parallel) {
            super(ph, supplier, parallel);
        }

        DoubleWrappingSpliterator(PipelineHelper<Double> ph,
                                  Spliterator<P_IN> spliterator,
                                  boolean parallel) {
            super(ph, spliterator, parallel);
        }

        @Override
        AbstractWrappingSpliterator<P_IN, Double, ?> wrap(Spliterator<P_IN> s) {
            return new DoubleWrappingSpliterator<>(ph, s, isParallel);
        }

        @Override
        void initPartialTraversalState() {
            SpinedBuffer.OfDouble b = new SpinedBuffer.OfDouble();
            buffer = b;
            bufferSink = ph.wrapSink((Sink.OfDouble) b::accept);
            pusher = () -> spliterator.tryAdvance(bufferSink);
        }

        @Override
        public Spliterator.OfDouble trySplit() {
            return (Spliterator.OfDouble) super.trySplit();
        }

        @Override
        public boolean tryAdvance(DoubleConsumer consumer) {
            Objects.requireNonNull(consumer);
            boolean hasNext = doAdvance();
            if (hasNext)
                consumer.accept(buffer.get(nextToConsume));
            return hasNext;
        }

        @Override
        public void forEachRemaining(DoubleConsumer consumer) {
            if (buffer == null && !finished) {
                Objects.requireNonNull(consumer);
                init();

                ph.wrapAndCopyInto((Sink.OfDouble) consumer::accept, spliterator);
                finished = true;
            }
            else {
                do { } while (tryAdvance(consumer));
            }
        }
    }

    /**
     * Spliterator implementation that delegates to an underlying spliterator,
     * acquiring the spliterator from a {@code Supplier<Spliterator>} on the
     * first call to any spliterator method.
     * @param <T>
     *     代理迭代器
     */
    static class DelegatingSpliterator<T, T_SPLITR extends Spliterator<T>>
            implements Spliterator<T> {
        private final Supplier<? extends T_SPLITR> supplier;

        /**
         * 迭代器引用
         */
        private T_SPLITR s;

        DelegatingSpliterator(Supplier<? extends T_SPLITR> supplier) {
            this.supplier = supplier;
        }

        T_SPLITR get() {
            if (s == null) {
                s = supplier.get();
            }
            return s;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T_SPLITR trySplit() {
            return (T_SPLITR) get().trySplit();
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> consumer) {
            return get().tryAdvance(consumer);
        }

        @Override
        public void forEachRemaining(Consumer<? super T> consumer) {
            get().forEachRemaining(consumer);
        }

        @Override
        public long estimateSize() {
            return get().estimateSize();
        }

        @Override
        public int characteristics() {
            return get().characteristics();
        }

        @Override
        public Comparator<? super T> getComparator() {
            return get().getComparator();
        }

        @Override
        public long getExactSizeIfKnown() {
            return get().getExactSizeIfKnown();
        }

        @Override
        public String toString() {
            return getClass().getName() + "[" + get() + "]";
        }

        static class OfPrimitive<T, T_CONS, T_SPLITR extends Spliterator.OfPrimitive<T, T_CONS, T_SPLITR>>
            extends DelegatingSpliterator<T, T_SPLITR>
            implements Spliterator.OfPrimitive<T, T_CONS, T_SPLITR> {
            OfPrimitive(Supplier<? extends T_SPLITR> supplier) {
                super(supplier);
            }

            @Override
            public boolean tryAdvance(T_CONS consumer) {
                return get().tryAdvance(consumer);
            }

            @Override
            public void forEachRemaining(T_CONS consumer) {
                get().forEachRemaining(consumer);
            }
        }

        static final class OfInt
                extends OfPrimitive<Integer, IntConsumer, Spliterator.OfInt>
                implements Spliterator.OfInt {

            OfInt(Supplier<Spliterator.OfInt> supplier) {
                super(supplier);
            }
        }

        static final class OfLong
                extends OfPrimitive<Long, LongConsumer, Spliterator.OfLong>
                implements Spliterator.OfLong {

            OfLong(Supplier<Spliterator.OfLong> supplier) {
                super(supplier);
            }
        }

        static final class OfDouble
                extends OfPrimitive<Double, DoubleConsumer, Spliterator.OfDouble>
                implements Spliterator.OfDouble {

            OfDouble(Supplier<Spliterator.OfDouble> supplier) {
                super(supplier);
            }
        }
    }

    /**
     * A slice Spliterator from a source Spliterator that reports
     * {@code SUBSIZED}.
     * 分片迭代器 对应到SUBSIZED
     */
    static abstract class SliceSpliterator<T, T_SPLITR extends Spliterator<T>> {
        // The start index of the slice
        final long sliceOrigin;
        // One past the last index of the slice
        final long sliceFence;

        // The spliterator to slice
        T_SPLITR s;
        // current (absolute) index, modified on advance/split
        long index;
        // one past last (absolute) index or sliceFence, which ever is smaller
        long fence;

        /**
         * 单分片对象 该对象 内不会有多个分片的情况
         * @param s    代表迭代器对象
         * @param sliceOrigin    代表分片的 起点 也就是 skip
         * @param sliceFence     代表分片的 长度大小 limit + skip
         * @param origin      代表 s 的起点 也就是0
         * @param fence       获取 s.length  和 sliceFence 的较小值
         */
        SliceSpliterator(T_SPLITR s, long sliceOrigin, long sliceFence, long origin, long fence) {
            assert s.hasCharacteristics(Spliterator.SUBSIZED);
            this.s = s;
            this.sliceOrigin = sliceOrigin;
            this.sliceFence = sliceFence;
            this.index = origin;
            this.fence = fence;
        }

        /**
         * 创建一个 分片迭代器
         * @param s
         * @param sliceOrigin
         * @param sliceFence
         * @param origin
         * @param fence
         * @return
         */
        protected abstract T_SPLITR makeSpliterator(T_SPLITR s, long sliceOrigin, long sliceFence, long origin, long fence);

        /**
         * 分裂
         * @return
         */
        public T_SPLITR trySplit() {
            // 可以翻译为 如果 skip 超过了 s.length 就不进行分片了
            if (sliceOrigin >= fence)
                return null;

            // 0 超过 s.length 也不进行分片  index 是可以变化的 每次分裂后 index 会推进  如果达到了 一开始设定的最大长度 那么也不允许拆分
            // fence 可能远远小于 s.length
            if (index >= fence)
                return null;

            // Keep splitting until the left and right splits intersect with the slice
            // thereby ensuring the size estimate decreases.
            // This also avoids creating empty spliterators which can result in
            // existing and additionally created F/J tasks that perform
            // redundant work on no elements.
            while (true) {
                // 分裂出前部分的迭代器
                @SuppressWarnings("unchecked")
                T_SPLITR leftSplit = (T_SPLITR) s.trySplit();
                // 代表无法分裂出元素
                if (leftSplit == null)
                    return null;

                // 更新 起点的值  leftSplitFenceUnbounded 代表 不考虑分片给相关的偏移量
                long leftSplitFenceUnbounded = index + leftSplit.estimateSize();
                // 代表 考虑 可能会超过分片的 情况  加以限制  不过也有可能都没有超过 skip 也就是没有碰到分片
                long leftSplitFence = Math.min(leftSplitFenceUnbounded, sliceFence);

                // 代表 切分下来的 范围都没有到 skip  并且会进入下次循环  没有切到分片 好像迭代器 直接被放弃了
                if (sliceOrigin >= leftSplitFence) {
                    // The left split does not intersect with, and is to the left of, the slice
                    // The right split does intersect
                    // Discard the left split and split further with the right split
                    // 这样 只要把 分片前的 数据直接分离出去就好 顺便更新 起点的偏移量
                    index = leftSplitFence;
                }
                // 代表分配的数据 超过了 分片的终点  这里不能确定起点是否在 分片的左侧也就是不能判断是否 出现重合  可以确定的是 右迭代器 肯定在 分片右侧
                else if (leftSplitFence >= sliceFence) {
                    // The right split does not intersect with, and is to the right of, the slice
                    // The left split does intersect
                    // Discard the right split and split further with the left split
                    // 代表抛弃了 分片右侧的数据
                    s = leftSplit;
                    // fence 原先是可能 远超 sliceFence 现在 变得靠近了 理解为 将分片外的 空间 抛弃掉
                    fence = leftSplitFence;
                }
                // index >= sliceOrigin 当出现 切分的 偏移量 == sliceOrigin 时会 出现 也就是 左迭代器的起点与 分片起点重合 而且 终点 <= 分片终点
                else if (index >= sliceOrigin && leftSplitFenceUnbounded <= sliceFence) {
                    // The left split is contained within the slice, return the underlying left split
                    // Right split is contained within or intersects with the slice
                    // index 变成了 分片 左侧被切掉一部分后 的右边界
                    index = leftSplitFence;
                    // 返回迭代器 也就是 这个迭代器 拆分的数据必须在 分片内才作数
                    return leftSplit;
                // 进入这里 3种情况 左包含 右包含  全包含 包含部分分片数据的迭代器是允许被返回的
                } else {
                    // The left split intersects with the slice
                    // Right split is contained within or intersects with the slice
                    // 构建一个新的 分片迭代器
                    return makeSpliterator(leftSplit, sliceOrigin, sliceFence, index, index = leftSplitFence);
                }
            }
        }

        public long estimateSize() {
            return (sliceOrigin < fence)
                   ? fence - Math.max(sliceOrigin, index) : 0;
        }

        public int characteristics() {
            return s.characteristics();
        }

        static final class OfRef<T>
                extends SliceSpliterator<T, Spliterator<T>>
                implements Spliterator<T> {

            /**
             *
             * @param s
             * @param sliceOrigin  代表分片距离  split的 起点的偏移量    对应 skip
             * @param sliceFence   代表从split 开始到 分片结束的 偏移量    对应 skip + limit
             */
            OfRef(Spliterator<T> s, long sliceOrigin, long sliceFence) {
                // orign 代表 split 的起点   fence 代表实际长度 因为limit 只是一个概念性限制 不代表真的有这么多空间
                this(s, sliceOrigin, sliceFence, 0, Math.min(s.estimateSize(), sliceFence));
            }

            private OfRef(Spliterator<T> s,
                          long sliceOrigin, long sliceFence, long origin, long fence) {
                super(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            protected Spliterator<T> makeSpliterator(Spliterator<T> s,
                                                     long sliceOrigin, long sliceFence,
                                                     long origin, long fence) {
                return new OfRef<>(s, sliceOrigin, sliceFence, origin, fence);
            }

            /**
             * 进行分裂
             * @param action The action
             * @return
             */
            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                Objects.requireNonNull(action);

                // 如果当前分片已经超过了 最大限制 无法获取下个元素
                if (sliceOrigin >= fence)
                    return false;

                // index 最后会指向sliOrign 相同的位置 也就是 迭代器 index -> sliceOrigin 的位置
                while (sliceOrigin > index) {
                    s.tryAdvance(e -> {});
                    index++;
                }

                if (index >= fence)
                    return false;

                // 正常情况下 增加index 这样 index > sliceOrigin
                index++;
                return s.tryAdvance(action);
            }

            @Override
            public void forEachRemaining(Consumer<? super T> action) {
                Objects.requireNonNull(action);

                // 如果 超过了 fence 就不需要进行遍历了
                if (sliceOrigin >= fence)
                    return;

                if (index >= fence)
                    return;

                // 代表 在 一个合理的范围内 就直接使用迭代器 的 forEach 方法
                if (index >= sliceOrigin && (index + s.estimateSize()) <= sliceFence) {
                    // The spliterator is contained within the slice
                    s.forEachRemaining(action);
                    index = fence;
                } else {
                    // The spliterator intersects with the slice
                    // 首先让 sliceOrigin 与 index 对齐
                    while (sliceOrigin > index) {
                        s.tryAdvance(e -> {});
                        index++;
                    }
                    // Traverse elements up to the fence
                    // 为每个元素执行消费者
                    for (;index < fence; index++) {
                        s.tryAdvance(action);
                    }
                }
            }
        }

        /**
         * 基于原始类型的 迭代器
         * @param <T>
         * @param <T_SPLITR>
         * @param <T_CONS>
         */
        static abstract class OfPrimitive<T,
                T_SPLITR extends Spliterator.OfPrimitive<T, T_CONS, T_SPLITR>,
                T_CONS>
                extends SliceSpliterator<T, T_SPLITR>
                implements Spliterator.OfPrimitive<T, T_CONS, T_SPLITR> {

            OfPrimitive(T_SPLITR s, long sliceOrigin, long sliceFence) {
                this(s, sliceOrigin, sliceFence, 0, Math.min(s.estimateSize(), sliceFence));
            }

            private OfPrimitive(T_SPLITR s,
                                long sliceOrigin, long sliceFence, long origin, long fence) {
                super(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            public boolean tryAdvance(T_CONS action) {
                Objects.requireNonNull(action);

                if (sliceOrigin >= fence)
                    return false;

                while (sliceOrigin > index) {
                    s.tryAdvance(emptyConsumer());
                    index++;
                }

                if (index >= fence)
                    return false;

                index++;
                return s.tryAdvance(action);
            }

            @Override
            public void forEachRemaining(T_CONS action) {
                Objects.requireNonNull(action);

                if (sliceOrigin >= fence)
                    return;

                if (index >= fence)
                    return;

                if (index >= sliceOrigin && (index + s.estimateSize()) <= sliceFence) {
                    // The spliterator is contained within the slice
                    s.forEachRemaining(action);
                    index = fence;
                } else {
                    // The spliterator intersects with the slice
                    while (sliceOrigin > index) {
                        s.tryAdvance(emptyConsumer());
                        index++;
                    }
                    // Traverse elements up to the fence
                    for (;index < fence; index++) {
                        s.tryAdvance(action);
                    }
                }
            }

            protected abstract T_CONS emptyConsumer();
        }

        static final class OfInt extends OfPrimitive<Integer, Spliterator.OfInt, IntConsumer>
                implements Spliterator.OfInt {
            OfInt(Spliterator.OfInt s, long sliceOrigin, long sliceFence) {
                super(s, sliceOrigin, sliceFence);
            }

            OfInt(Spliterator.OfInt s,
                  long sliceOrigin, long sliceFence, long origin, long fence) {
                super(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            protected Spliterator.OfInt makeSpliterator(Spliterator.OfInt s,
                                                        long sliceOrigin, long sliceFence,
                                                        long origin, long fence) {
                return new SliceSpliterator.OfInt(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            protected IntConsumer emptyConsumer() {
                return e -> {};
            }
        }

        static final class OfLong extends OfPrimitive<Long, Spliterator.OfLong, LongConsumer>
                implements Spliterator.OfLong {
            OfLong(Spliterator.OfLong s, long sliceOrigin, long sliceFence) {
                super(s, sliceOrigin, sliceFence);
            }

            OfLong(Spliterator.OfLong s,
                   long sliceOrigin, long sliceFence, long origin, long fence) {
                super(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            protected Spliterator.OfLong makeSpliterator(Spliterator.OfLong s,
                                                         long sliceOrigin, long sliceFence,
                                                         long origin, long fence) {
                return new SliceSpliterator.OfLong(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            protected LongConsumer emptyConsumer() {
                return e -> {};
            }
        }

        static final class OfDouble extends OfPrimitive<Double, Spliterator.OfDouble, DoubleConsumer>
                implements Spliterator.OfDouble {
            OfDouble(Spliterator.OfDouble s, long sliceOrigin, long sliceFence) {
                super(s, sliceOrigin, sliceFence);
            }

            OfDouble(Spliterator.OfDouble s,
                     long sliceOrigin, long sliceFence, long origin, long fence) {
                super(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            protected Spliterator.OfDouble makeSpliterator(Spliterator.OfDouble s,
                                                           long sliceOrigin, long sliceFence,
                                                           long origin, long fence) {
                return new SliceSpliterator.OfDouble(s, sliceOrigin, sliceFence, origin, fence);
            }

            @Override
            protected DoubleConsumer emptyConsumer() {
                return e -> {};
            }
        }
    }

    /**
     * A slice Spliterator that does not preserve order, if any, of a source
     * Spliterator.
     *
     * Note: The source spliterator may report {@code ORDERED} since that
     * spliterator be the result of a previous pipeline stage that was
     * collected to a {@code Node}. It is the order of the pipeline stage
     * that governs whether the this slice spliterator is to be used or not.
     * 无序的分片迭代器
     */
    static abstract class UnorderedSliceSpliterator<T, T_SPLITR extends Spliterator<T>> {
        /**
         * 代表每个 CHUNK 的 推荐大小
         */
        static final int CHUNK_SIZE = 1 << 7;

        // The spliterator to slice
        /**
         * 分片迭代器实例对象
         */
        protected final T_SPLITR s;
        /**
         * 是否限制大小
         */
        protected final boolean unlimited;
        /**
         * 最多 skip 只能设置成多大
         */
        private final long skipThreshold;
        /**
         * 许可证数量???
         */
        private final AtomicLong permits;

        /**
         * 在初始化时 会设置 skip 和limit 且 permits 是 2个字段和
         * @param s
         * @param skip
         * @param limit
         */
        UnorderedSliceSpliterator(T_SPLITR s, long skip, long limit) {
            this.s = s;
            this.unlimited = limit < 0;
            // 最多只能跳过这么多元素
            this.skipThreshold = limit >= 0 ? limit : 0;
            this.permits = new AtomicLong(limit >= 0 ? skip + limit : skip);
        }

        UnorderedSliceSpliterator(T_SPLITR s,
                                  UnorderedSliceSpliterator<T, T_SPLITR> parent) {
            this.s = s;
            this.unlimited = parent.unlimited;
            this.permits = parent.permits;
            this.skipThreshold = parent.skipThreshold;
        }

        /**
         * Acquire permission to skip or process elements.  The caller must
         * first acquire the elements, then consult this method for guidance
         * as to what to do with the data.
         *
         * <p>We use an {@code AtomicLong} to atomically maintain a counter,
         * which is initialized as skip+limit if we are limiting, or skip only
         * if we are not limiting.  The user should consult the method
         * {@code checkPermits()} before acquiring data elements.
         *
         * @param numElements the number of elements the caller has in hand
         * @return the number of elements that should be processed; any
         * remaining elements should be discarded.
         * 尝试获取许可证
         */
        protected final long acquirePermits(long numElements) {
            // 记录还剩下多少许可证
            long remainingPermits;
            // 代表本次获取了多少
            long grabbing;
            // permits never increase, and don't decrease below zero
            assert numElements > 0;
            do {
                // 当前没有剩余的许可证了
                remainingPermits = permits.get();
                if (remainingPermits == 0)
                    // 如果没有限制 直接返回 对应的数量 否则返回0
                    return unlimited ? numElements : 0;
                // 最多只能获取 剩余的量
                grabbing = Math.min(remainingPermits, numElements);
            } while (grabbing > 0 &&
                    // 如果CAS 操作失败 代表没有成功获取到 许可证
                     !permits.compareAndSet(remainingPermits, remainingPermits - grabbing));

            if (unlimited)
                // numElements - grabbing 可能会返回 0
                return Math.max(numElements - grabbing, 0);
            else if (remainingPermits > skipThreshold)
                return Math.max(grabbing - (remainingPermits - skipThreshold), 0);
            else
                // 返回获得的许可证数量
                return grabbing;
        }

        /**
         * 对应 permit 的状态  NO_MORE 代表没有元素了  MAYBE_MORE 代表还有元素 UNLIMITED 代表没有限制
         */
        enum PermitStatus { NO_MORE, MAYBE_MORE, UNLIMITED }

        /** Call to check if permits might be available before acquiring data */
        protected final PermitStatus permitStatus() {
            if (permits.get() > 0)
                return PermitStatus.MAYBE_MORE;
            else
                return unlimited ?  PermitStatus.UNLIMITED : PermitStatus.NO_MORE;
        }

        /**
         * 创建迭代器
         * @return
         */
        public final T_SPLITR trySplit() {
            // Stop splitting when there are no more limit permits
            if (permits.get() == 0)
                return null;
            @SuppressWarnings("unchecked")
            T_SPLITR split = (T_SPLITR) s.trySplit();
            // 包装迭代器
            return split == null ? null : makeSpliterator(split);
        }

        protected abstract T_SPLITR makeSpliterator(T_SPLITR s);

        public final long estimateSize() {
            return s.estimateSize();
        }

        public final int characteristics() {
            return s.characteristics() &
                   ~(Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED);
        }

        static final class OfRef<T> extends UnorderedSliceSpliterator<T, Spliterator<T>>
                implements Spliterator<T>, Consumer<T> {
            T tmpSlot;

            OfRef(Spliterator<T> s, long skip, long limit) {
                super(s, skip, limit);
            }

            OfRef(Spliterator<T> s, OfRef<T> parent) {
                super(s, parent);
            }

            /**
             * 实现消费者接口 将内部 元素 指向 传入元素
             * @param t the input argument
             */
            @Override
            public final void accept(T t) {
                tmpSlot = t;
            }

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                Objects.requireNonNull(action);

                while (permitStatus() != PermitStatus.NO_MORE) {
                    if (!s.tryAdvance(this))
                        return false;
                    // 代表获取到了元素  消耗当前维护元素 并且 必须执行过一次 accept 才能成功执行 tryAdvance
                    else if (acquirePermits(1) == 1) {
                        action.accept(tmpSlot);
                        tmpSlot = null;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void forEachRemaining(Consumer<? super T> action) {
                Objects.requireNonNull(action);

                ArrayBuffer.OfRef<T> sb = null;
                PermitStatus permitStatus;
                while ((permitStatus = permitStatus()) != PermitStatus.NO_MORE) {
                    if (permitStatus == PermitStatus.MAYBE_MORE) {
                        // Optimistically traverse elements up to a threshold of CHUNK_SIZE
                        if (sb == null)
                            // 创建一个 指定大小的容器
                            sb = new ArrayBuffer.OfRef<>(CHUNK_SIZE);
                        else
                            sb.reset();
                        long permitsRequested = 0;
                        // sb.accept 会将 元素填充到容器中
                        do { } while (s.tryAdvance(sb) && ++permitsRequested < CHUNK_SIZE);
                        // 没有获取到任何元素 直接返回
                        if (permitsRequested == 0)
                            return;
                        // 从0 到 permitsRequested 的所有元素 执行consumer
                        sb.forEach(action, acquirePermits(permitsRequested));
                    }
                    else {
                        // Must be UNLIMITED; let 'er rip
                        // 对应 UNLIMITED
                        s.forEachRemaining(action);
                        return;
                    }
                }
            }

            /**
             * 生成 一个无序的 分片迭代器对象
             * @param s
             * @return
             */
            @Override
            protected Spliterator<T> makeSpliterator(Spliterator<T> s) {
                return new UnorderedSliceSpliterator.OfRef<>(s, this);
            }
        }

        /**
         * Concrete sub-types must also be an instance of type {@code T_CONS}.
         *
         * @param <T_BUFF> the type of the spined buffer. Must also be a type of
         *        {@code T_CONS}.
         */
        static abstract class OfPrimitive<
                T,
                T_CONS,
                T_BUFF extends ArrayBuffer.OfPrimitive<T_CONS>,
                T_SPLITR extends Spliterator.OfPrimitive<T, T_CONS, T_SPLITR>>
                extends UnorderedSliceSpliterator<T, T_SPLITR>
                implements Spliterator.OfPrimitive<T, T_CONS, T_SPLITR> {
            OfPrimitive(T_SPLITR s, long skip, long limit) {
                super(s, skip, limit);
            }

            OfPrimitive(T_SPLITR s, UnorderedSliceSpliterator.OfPrimitive<T, T_CONS, T_BUFF, T_SPLITR> parent) {
                super(s, parent);
            }

            @Override
            public boolean tryAdvance(T_CONS action) {
                Objects.requireNonNull(action);
                @SuppressWarnings("unchecked")
                T_CONS consumer = (T_CONS) this;

                while (permitStatus() != PermitStatus.NO_MORE) {
                    if (!s.tryAdvance(consumer))
                        return false;
                    else if (acquirePermits(1) == 1) {
                        acceptConsumed(action);
                        return true;
                    }
                }
                return false;
            }

            protected abstract void acceptConsumed(T_CONS action);

            @Override
            public void forEachRemaining(T_CONS action) {
                Objects.requireNonNull(action);

                T_BUFF sb = null;
                PermitStatus permitStatus;
                while ((permitStatus = permitStatus()) != PermitStatus.NO_MORE) {
                    if (permitStatus == PermitStatus.MAYBE_MORE) {
                        // Optimistically traverse elements up to a threshold of CHUNK_SIZE
                        if (sb == null)
                            sb = bufferCreate(CHUNK_SIZE);
                        else
                            sb.reset();
                        @SuppressWarnings("unchecked")
                        T_CONS sbc = (T_CONS) sb;
                        long permitsRequested = 0;
                        do { } while (s.tryAdvance(sbc) && ++permitsRequested < CHUNK_SIZE);
                        if (permitsRequested == 0)
                            return;
                        sb.forEach(action, acquirePermits(permitsRequested));
                    }
                    else {
                        // Must be UNLIMITED; let 'er rip
                        s.forEachRemaining(action);
                        return;
                    }
                }
            }

            protected abstract T_BUFF bufferCreate(int initialCapacity);
        }

        static final class OfInt
                extends OfPrimitive<Integer, IntConsumer, ArrayBuffer.OfInt, Spliterator.OfInt>
                implements Spliterator.OfInt, IntConsumer {

            int tmpValue;

            OfInt(Spliterator.OfInt s, long skip, long limit) {
                super(s, skip, limit);
            }

            OfInt(Spliterator.OfInt s, UnorderedSliceSpliterator.OfInt parent) {
                super(s, parent);
            }

            @Override
            public void accept(int value) {
                tmpValue = value;
            }

            @Override
            protected void acceptConsumed(IntConsumer action) {
                action.accept(tmpValue);
            }

            @Override
            protected ArrayBuffer.OfInt bufferCreate(int initialCapacity) {
                return new ArrayBuffer.OfInt(initialCapacity);
            }

            @Override
            protected Spliterator.OfInt makeSpliterator(Spliterator.OfInt s) {
                return new UnorderedSliceSpliterator.OfInt(s, this);
            }
        }

        static final class OfLong
                extends OfPrimitive<Long, LongConsumer, ArrayBuffer.OfLong, Spliterator.OfLong>
                implements Spliterator.OfLong, LongConsumer {

            long tmpValue;

            OfLong(Spliterator.OfLong s, long skip, long limit) {
                super(s, skip, limit);
            }

            OfLong(Spliterator.OfLong s, UnorderedSliceSpliterator.OfLong parent) {
                super(s, parent);
            }

            @Override
            public void accept(long value) {
                tmpValue = value;
            }

            @Override
            protected void acceptConsumed(LongConsumer action) {
                action.accept(tmpValue);
            }

            @Override
            protected ArrayBuffer.OfLong bufferCreate(int initialCapacity) {
                return new ArrayBuffer.OfLong(initialCapacity);
            }

            @Override
            protected Spliterator.OfLong makeSpliterator(Spliterator.OfLong s) {
                return new UnorderedSliceSpliterator.OfLong(s, this);
            }
        }

        static final class OfDouble
                extends OfPrimitive<Double, DoubleConsumer, ArrayBuffer.OfDouble, Spliterator.OfDouble>
                implements Spliterator.OfDouble, DoubleConsumer {

            double tmpValue;

            OfDouble(Spliterator.OfDouble s, long skip, long limit) {
                super(s, skip, limit);
            }

            OfDouble(Spliterator.OfDouble s, UnorderedSliceSpliterator.OfDouble parent) {
                super(s, parent);
            }

            @Override
            public void accept(double value) {
                tmpValue = value;
            }

            @Override
            protected void acceptConsumed(DoubleConsumer action) {
                action.accept(tmpValue);
            }

            @Override
            protected ArrayBuffer.OfDouble bufferCreate(int initialCapacity) {
                return new ArrayBuffer.OfDouble(initialCapacity);
            }

            @Override
            protected Spliterator.OfDouble makeSpliterator(Spliterator.OfDouble s) {
                return new UnorderedSliceSpliterator.OfDouble(s, this);
            }
        }
    }

    /**
     * A wrapping spliterator that only reports distinct elements of the
     * underlying spliterator. Does not preserve size and encounter order.
     * 不同的 迭代器???
     */
    static final class DistinctSpliterator<T> implements Spliterator<T>, Consumer<T> {

        // The value to represent null in the ConcurrentHashMap
        /**
         * 用于 代表 null的含义
         */
        private static final Object NULL_VALUE = new Object();

        // The underlying spliterator
        /**
         * 下层的迭代器
         */
        private final Spliterator<T> s;

        // ConcurrentHashMap holding distinct elements as keys
        /**
         * 通过key 来区分不同的 元素
         */
        private final ConcurrentHashMap<T, Boolean> seen;

        // Temporary element, only used with tryAdvance、
        /**
         * 临时元素 只有在调用 tryAdvance时使用
         */
        private T tmpSlot;

        DistinctSpliterator(Spliterator<T> s) {
            this(s, new ConcurrentHashMap<>());
        }

        private DistinctSpliterator(Spliterator<T> s, ConcurrentHashMap<T, Boolean> seen) {
            this.s = s;
            this.seen = seen;
        }

        /**
         * 触发消费者的方法时 将tempSlot 指向传入元素
         * @param t the input argument
         */
        @Override
        public void accept(T t) {
            this.tmpSlot = t;
        }

        @SuppressWarnings("unchecked")
        private T mapNull(T t) {
            return t != null ? t : (T) NULL_VALUE;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            // 代表从迭代器中抽取元素
            while (s.tryAdvance(this)) {
                // 返回为null 代表之前没有设置其他值
                if (seen.putIfAbsent(mapNull(tmpSlot), Boolean.TRUE) == null) {
                    action.accept(tmpSlot);
                    tmpSlot = null;
                    return true;
                }
            }
            return false;
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            // 只有添加成功的情况下 才使用 消费者处理
            s.forEachRemaining(t -> {
                if (seen.putIfAbsent(mapNull(t), Boolean.TRUE) == null) {
                    action.accept(t);
                }
            });
        }

        @Override
        public Spliterator<T> trySplit() {
            Spliterator<T> split = s.trySplit();
            return (split != null) ? new DistinctSpliterator<>(split, seen) : null;
        }

        @Override
        public long estimateSize() {
            return s.estimateSize();
        }

        @Override
        public int characteristics() {
            return (s.characteristics() & ~(Spliterator.SIZED | Spliterator.SUBSIZED |
                                            Spliterator.SORTED | Spliterator.ORDERED))
                   | Spliterator.DISTINCT;
        }

        @Override
        public Comparator<? super T> getComparator() {
            return s.getComparator();
        }
    }

    /**
     * A Spliterator that infinitely supplies elements in no particular order.
     *
     * <p>Splitting divides the estimated size in two and stops when the
     * estimate size is 0.
     *
     * <p>The {@code forEachRemaining} method if invoked will never terminate.
     * The {@code tryAdvance} method always returns true.
     * 无限的 提供者???
     */
    static abstract class InfiniteSupplyingSpliterator<T> implements Spliterator<T> {

        /**
         * 长度???
         */
        long estimate;

        protected InfiniteSupplyingSpliterator(long estimate) {
            this.estimate = estimate;
        }

        @Override
        public long estimateSize() {
            return estimate;
        }

        @Override
        public int characteristics() {
            return IMMUTABLE;
        }

        static final class OfRef<T> extends InfiniteSupplyingSpliterator<T> {
            final Supplier<T> s;

            OfRef(long size, Supplier<T> s) {
                super(size);
                this.s = s;
            }

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                Objects.requireNonNull(action);

                action.accept(s.get());
                return true;
            }

            @Override
            public Spliterator<T> trySplit() {
                if (estimate == 0)
                    return null;
                // 返回一个 将长度减半后的 新迭代器对象
                return new InfiniteSupplyingSpliterator.OfRef<>(estimate >>>= 1, s);
            }
        }

        static final class OfInt extends InfiniteSupplyingSpliterator<Integer>
                implements Spliterator.OfInt {
            final IntSupplier s;

            OfInt(long size, IntSupplier s) {
                super(size);
                this.s = s;
            }

            @Override
            public boolean tryAdvance(IntConsumer action) {
                Objects.requireNonNull(action);

                action.accept(s.getAsInt());
                return true;
            }

            @Override
            public Spliterator.OfInt trySplit() {
                if (estimate == 0)
                    return null;
                return new InfiniteSupplyingSpliterator.OfInt(estimate = estimate >>> 1, s);
            }
        }

        static final class OfLong extends InfiniteSupplyingSpliterator<Long>
                implements Spliterator.OfLong {
            final LongSupplier s;

            OfLong(long size, LongSupplier s) {
                super(size);
                this.s = s;
            }

            @Override
            public boolean tryAdvance(LongConsumer action) {
                Objects.requireNonNull(action);

                action.accept(s.getAsLong());
                return true;
            }

            @Override
            public Spliterator.OfLong trySplit() {
                if (estimate == 0)
                    return null;
                return new InfiniteSupplyingSpliterator.OfLong(estimate = estimate >>> 1, s);
            }
        }

        static final class OfDouble extends InfiniteSupplyingSpliterator<Double>
                implements Spliterator.OfDouble {
            final DoubleSupplier s;

            OfDouble(long size, DoubleSupplier s) {
                super(size);
                this.s = s;
            }

            @Override
            public boolean tryAdvance(DoubleConsumer action) {
                Objects.requireNonNull(action);

                action.accept(s.getAsDouble());
                return true;
            }

            @Override
            public Spliterator.OfDouble trySplit() {
                if (estimate == 0)
                    return null;
                return new InfiniteSupplyingSpliterator.OfDouble(estimate = estimate >>> 1, s);
            }
        }
    }

    // @@@ Consolidate with Node.Builder

    /**
     * 基于数组的 buffer实现
     */
    static abstract class ArrayBuffer {
        /**
         * 数组下标
         */
        int index;

        void reset() {
            index = 0;
        }

        static final class OfRef<T> extends ArrayBuffer implements Consumer<T> {
            final Object[] array;

            OfRef(int size) {
                this.array = new Object[size];
            }

            /**
             * 将元素填充到 数组中
             * @param t the input argument
             */
            @Override
            public void accept(T t) {
                array[index++] = t;
            }

            /**
             * 从 index = 0 开始 到fence 为止 为所有元素执行 accept
             * @param action
             * @param fence
             */
            public void forEach(Consumer<? super T> action, long fence) {
                for (int i = 0; i < fence; i++) {
                    @SuppressWarnings("unchecked")
                    T t = (T) array[i];
                    action.accept(t);
                }
            }
        }

        static abstract class OfPrimitive<T_CONS> extends ArrayBuffer {
            int index;

            @Override
            void reset() {
                index = 0;
            }

            abstract void forEach(T_CONS action, long fence);
        }

        static final class OfInt extends OfPrimitive<IntConsumer>
                implements IntConsumer {
            final int[] array;

            OfInt(int size) {
                this.array = new int[size];
            }

            @Override
            public void accept(int t) {
                array[index++] = t;
            }

            @Override
            public void forEach(IntConsumer action, long fence) {
                for (int i = 0; i < fence; i++) {
                    action.accept(array[i]);
                }
            }
        }

        static final class OfLong extends OfPrimitive<LongConsumer>
                implements LongConsumer {
            final long[] array;

            OfLong(int size) {
                this.array = new long[size];
            }

            @Override
            public void accept(long t) {
                array[index++] = t;
            }

            @Override
            public void forEach(LongConsumer action, long fence) {
                for (int i = 0; i < fence; i++) {
                    action.accept(array[i]);
                }
            }
        }

        static final class OfDouble extends OfPrimitive<DoubleConsumer>
                implements DoubleConsumer {
            final double[] array;

            OfDouble(int size) {
                this.array = new double[size];
            }

            @Override
            public void accept(double t) {
                array[index++] = t;
            }

            @Override
            void forEach(DoubleConsumer action, long fence) {
                for (int i = 0; i < fence; i++) {
                    action.accept(array[i]);
                }
            }
        }
    }
}

