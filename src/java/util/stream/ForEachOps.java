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

import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;

/**
 * Factory for creating instances of {@code TerminalOp} that perform an
 * action for every element of a stream.  Supported variants include unordered
 * traversal (elements are provided to the {@code Consumer} as soon as they are
 * available), and ordered traversal (elements are provided to the
 * {@code Consumer} in encounter order.)
 *
 * <p>Elements are provided to the {@code Consumer} on whatever thread and
 * whatever order they become available.  For ordered traversals, it is
 * guaranteed that processing an element <em>happens-before</em> processing
 * subsequent elements in the encounter order.
 *
 * <p>Exceptions occurring as a result of sending an element to the
 * {@code Consumer} will be relayed to the caller and traversal will be
 * prematurely terminated.
 *
 * @since 1.8
 * 针对 forEach 的操作
 */
final class ForEachOps {

    private ForEachOps() { }

    /**
     * Constructs a {@code TerminalOp} that perform an action for every element
     * of a stream.
     *
     * @param action the {@code Consumer} that receives all elements of a
     *        stream
     * @param ordered whether an ordered traversal is requested
     * @param <T> the type of the stream elements
     * @return the {@code TerminalOp} instance
     */
    public static <T> TerminalOp<T, Void> makeRef(Consumer<? super T> action,
                                                  boolean ordered) {
        Objects.requireNonNull(action);
        // 通过 consumer 和 是否 顺序构建一个 forEach 对象
        return new ForEachOp.OfRef<>(action, ordered);
    }

    /**
     * Constructs a {@code TerminalOp} that perform an action for every element
     * of an {@code IntStream}.
     *
     * @param action the {@code IntConsumer} that receives all elements of a
     *        stream
     * @param ordered whether an ordered traversal is requested
     * @return the {@code TerminalOp} instance
     */
    public static TerminalOp<Integer, Void> makeInt(IntConsumer action,
                                                    boolean ordered) {
        Objects.requireNonNull(action);
        return new ForEachOp.OfInt(action, ordered);
    }

    /**
     * Constructs a {@code TerminalOp} that perform an action for every element
     * of a {@code LongStream}.
     *
     * @param action the {@code LongConsumer} that receives all elements of a
     *        stream
     * @param ordered whether an ordered traversal is requested
     * @return the {@code TerminalOp} instance
     */
    public static TerminalOp<Long, Void> makeLong(LongConsumer action,
                                                  boolean ordered) {
        Objects.requireNonNull(action);
        return new ForEachOp.OfLong(action, ordered);
    }

    /**
     * Constructs a {@code TerminalOp} that perform an action for every element
     * of a {@code DoubleStream}.
     *
     * @param action the {@code DoubleConsumer} that receives all elements of
     *        a stream
     * @param ordered whether an ordered traversal is requested
     * @return the {@code TerminalOp} instance
     */
    public static TerminalOp<Double, Void> makeDouble(DoubleConsumer action,
                                                      boolean ordered) {
        Objects.requireNonNull(action);
        return new ForEachOp.OfDouble(action, ordered);
    }

    /**
     * A {@code TerminalOp} that evaluates a stream pipeline and sends the
     * output to itself as a {@code TerminalSink}.  Elements will be sent in
     * whatever thread they become available.  If the traversal is unordered,
     * they will be sent independent of the stream's encounter order.
     *
     * <p>This terminal operation is stateless.  For parallel evaluation, each
     * leaf instance of a {@code ForEachTask} will send elements to the same
     * {@code TerminalSink} reference that is an instance of this class.
     *
     * @param <T> the output type of the stream pipeline
     *           forEach 操作对象
     */
    static abstract class ForEachOp<T>
            implements TerminalOp<T, Void>, TerminalSink<T, Void> {

        /**
         * 判断 某部容器对象是否是有序的
         */
        private final boolean ordered;

        protected ForEachOp(boolean ordered) {
            this.ordered = ordered;
        }

        // TerminalOp

        @Override
        public int getOpFlags() {
            return ordered ? 0 : StreamOpFlag.NOT_ORDERED;
        }

        /**
         * 串行操作 就是将迭代器内的数据 拷贝到本对象  Sink 对象本身是具备存储数据的能力的 通过 begin 和end 方法 还有 accept
         * @param helper the pipeline helper
         * @param spliterator the source spliterator
         * @param <S>
         * @return
         */
        @Override
        public <S> Void evaluateSequential(PipelineHelper<T> helper,
                                           Spliterator<S> spliterator) {
            return helper.wrapAndCopyInto(this, spliterator).get();
        }

        /**
         * 并行执行 都是通过创建对应的 task 对象后 执行
         * @param helper the pipeline helper
         * @param spliterator the source spliterator
         * @param <S>
         * @return
         */
        @Override
        public <S> Void evaluateParallel(PipelineHelper<T> helper,
                                         Spliterator<S> spliterator) {
            if (ordered)
                new ForEachOrderedTask<>(helper, spliterator, this).invoke();
            else
                // wrapSink 将本对象包装后返回
                new ForEachTask<>(helper, spliterator, helper.wrapSink(this)).invoke();
            return null;
        }

        // TerminalSink

        @Override
        public Void get() {
            return null;
        }

        // Implementations

        /** Implementation class for reference streams */
        static final class OfRef<T> extends ForEachOp<T> {

            /**
             * 内部存储的 消费者对象
             */
            final Consumer<? super T> consumer;

            OfRef(Consumer<? super T> consumer, boolean ordered) {
                super(ordered);
                this.consumer = consumer;
            }

            /**
             * 将 添加数据的逻辑 委托到 consumer 对象上
             * @param t the input argument
             */
            @Override
            public void accept(T t) {
                consumer.accept(t);
            }
        }

        /** Implementation class for {@code IntStream} */
        static final class OfInt extends ForEachOp<Integer>
                implements Sink.OfInt {
            final IntConsumer consumer;

            OfInt(IntConsumer consumer, boolean ordered) {
                super(ordered);
                this.consumer = consumer;
            }

            @Override
            public StreamShape inputShape() {
                return StreamShape.INT_VALUE;
            }

            @Override
            public void accept(int t) {
                consumer.accept(t);
            }
        }

        /** Implementation class for {@code LongStream} */
        static final class OfLong extends ForEachOp<Long>
                implements Sink.OfLong {
            final LongConsumer consumer;

            OfLong(LongConsumer consumer, boolean ordered) {
                super(ordered);
                this.consumer = consumer;
            }

            @Override
            public StreamShape inputShape() {
                return StreamShape.LONG_VALUE;
            }

            @Override
            public void accept(long t) {
                consumer.accept(t);
            }
        }

        /** Implementation class for {@code DoubleStream} */
        static final class OfDouble extends ForEachOp<Double>
                implements Sink.OfDouble {
            final DoubleConsumer consumer;

            OfDouble(DoubleConsumer consumer, boolean ordered) {
                super(ordered);
                this.consumer = consumer;
            }

            @Override
            public StreamShape inputShape() {
                return StreamShape.DOUBLE_VALUE;
            }

            @Override
            public void accept(double t) {
                consumer.accept(t);
            }
        }
    }

    /** A {@code ForkJoinTask} for performing a parallel for-each operation */
    /**
     * 用于处理并行任务的 task 对象
     * @param <S>
     * @param <T>
     */
    @SuppressWarnings("serial")
    static final class ForEachTask<S, T> extends CountedCompleter<Void> {
        /**
         * 包含数据的迭代器对象
         */
        private Spliterator<S> spliterator;
        /**
         * 数据容器
         */
        private final Sink<S> sink;
        /**
         * 控制数据流程
         */
        private final PipelineHelper<T> helper;
        /**
         * 尺寸???
         */
        private long targetSize;

        ForEachTask(PipelineHelper<T> helper,
                    Spliterator<S> spliterator,
                    Sink<S> sink) {
            super(null);
            this.sink = sink;
            this.helper = helper;
            this.spliterator = spliterator;
            this.targetSize = 0L;
        }

        ForEachTask(ForEachTask<S, T> parent, Spliterator<S> spliterator) {
            super(parent);
            this.spliterator = spliterator;
            this.sink = parent.sink;
            this.targetSize = parent.targetSize;
            this.helper = parent.helper;
        }

        // Similar to AbstractTask but doesn't need to track child tasks
        public void compute() {
            Spliterator<S> rightSplit = spliterator, leftSplit;
            // 获取 右侧迭代器 也就是 spliterator 的长度信息
            long sizeEstimate = rightSplit.estimateSize(), sizeThreshold;
            if ((sizeThreshold = targetSize) == 0L)
                // 获取一个 推荐大小
                targetSize = sizeThreshold = AbstractTask.suggestTargetSize(sizeEstimate);
            // 判断是否短路
            boolean isShortCircuit = StreamOpFlag.SHORT_CIRCUIT.isKnown(helper.getStreamAndOpFlags());
            // 是否拆分右侧
            boolean forkRight = false;
            Sink<S> taskSink = sink;
            ForEachTask<S, T> task = this;
            // 没有短路 且能 接受请求时
            while (!isShortCircuit || !taskSink.cancellationRequested()) {
                // 代表不能再拆分了
                if (sizeEstimate <= sizeThreshold ||
                    (leftSplit = rightSplit.trySplit()) == null) {
                    task.helper.copyInto(taskSink, rightSplit);
                    break;
                }
                // 初始化一个 左节点对象 并以 task 作为 父节点
                ForEachTask<S, T> leftTask = new ForEachTask<>(task, leftSplit);
                // 增加父节点的悬挂节点
                task.addToPendingCount(1);
                ForEachTask<S, T> taskToFork;
                if (forkRight) {
                    forkRight = false;
                    // 下次针对左节点进行拆分
                    rightSplit = leftSplit;
                    taskToFork = task;
                    task = leftTask;
                }
                else {
                    forkRight = true;
                    taskToFork = leftTask;
                }
                taskToFork.fork();
                sizeEstimate = rightSplit.estimateSize();
            }
            task.spliterator = null;
            // 调用了 fork 难道任务就完成了???
            task.propagateCompletion();
        }
    }

    /**
     * A {@code ForkJoinTask} for performing a parallel for-each operation
     * which visits the elements in encounter order
     * 顺序并行任务
     */
    @SuppressWarnings("serial")
    static final class ForEachOrderedTask<S, T> extends CountedCompleter<Void> {
        /*
         * Our goal is to ensure that the elements associated with a task are
         * processed according to an in-order traversal of the computation tree.
         * We use completion counts for representing these dependencies, so that
         * a task does not complete until all the tasks preceding it in this
         * order complete.  We use the "completion map" to associate the next
         * task in this order for any left child.  We increase the pending count
         * of any node on the right side of such a mapping by one to indicate
         * its dependency, and when a node on the left side of such a mapping
         * completes, it decrements the pending count of its corresponding right
         * side.  As the computation tree is expanded by splitting, we must
         * atomically update the mappings to maintain the invariant that the
         * completion map maps left children to the next node in the in-order
         * traversal.
         *
         * Take, for example, the following computation tree of tasks:
         *
         *       a
         *      / \
         *     b   c
         *    / \ / \
         *   d  e f  g
         *
         * The complete map will contain (not necessarily all at the same time)
         * the following associations:
         *
         *   d -> e
         *   b -> f
         *   f -> g
         *
         * Tasks e, f, g will have their pending counts increased by 1.
         *
         * The following relationships hold:
         *
         *   - completion of d "happens-before" e;
         *   - completion of d and e "happens-before b;
         *   - completion of b "happens-before" f; and
         *   - completion of f "happens-before" g
         *
         * Thus overall the "happens-before" relationship holds for the
         * reporting of elements, covered by tasks d, e, f and g, as specified
         * by the forEachOrdered operation.
         * 按照上面的说明 好像执行顺序是  d -> e -> b -> f -> g -> c -> a
         */

        private final PipelineHelper<T> helper;
        private Spliterator<S> spliterator;
        private final long targetSize;
        /**
         * 计算容器
         */
        private final ConcurrentHashMap<ForEachOrderedTask<S, T>, ForEachOrderedTask<S, T>> completionMap;
        private final Sink<T> action;
        /**
         * 左处理器???
         */
        private final ForEachOrderedTask<S, T> leftPredecessor;
        private Node<T> node;

        protected ForEachOrderedTask(PipelineHelper<T> helper,
                                     Spliterator<S> spliterator,
                                     Sink<T> action) {
            super(null);
            this.helper = helper;
            this.spliterator = spliterator;
            this.targetSize = AbstractTask.suggestTargetSize(spliterator.estimateSize());
            // Size map to avoid concurrent re-sizes
            // 获取容器大小
            this.completionMap = new ConcurrentHashMap<>(Math.max(16, AbstractTask.LEAF_TARGET << 1));
            this.action = action;
            this.leftPredecessor = null;
        }

        /**
         * 通过一个 左处理器进行初始化
         * @param parent
         * @param spliterator
         * @param leftPredecessor
         */
        ForEachOrderedTask(ForEachOrderedTask<S, T> parent,
                           Spliterator<S> spliterator,
                           ForEachOrderedTask<S, T> leftPredecessor) {
            super(parent);
            this.helper = parent.helper;
            this.spliterator = spliterator;
            this.targetSize = parent.targetSize;
            this.completionMap = parent.completionMap;
            this.action = parent.action;
            this.leftPredecessor = leftPredecessor;
        }

        @Override
        public final void compute() {
            doCompute(this);
        }

        /**
         * 计算任务
         * @param task
         * @param <S>
         * @param <T>
         */
        private static <S, T> void doCompute(ForEachOrderedTask<S, T> task) {
            Spliterator<S> rightSplit = task.spliterator, leftSplit;
            long sizeThreshold = task.targetSize;
            boolean forkRight = false;
            while (rightSplit.estimateSize() > sizeThreshold &&
                   (leftSplit = rightSplit.trySplit()) != null) {
                ForEachOrderedTask<S, T> leftChild =
                    new ForEachOrderedTask<>(task, leftSplit, task.leftPredecessor);
                ForEachOrderedTask<S, T> rightChild =
                        // 将左节点 作为右对象的 leftPredecessor
                    new ForEachOrderedTask<>(task, rightSplit, leftChild);

                // Fork the parent task
                // Completion of the left and right children "happens-before"
                // completion of the parent
                task.addToPendingCount(1);
                // Completion of the left child "happens-before" completion of
                // the right child
                // 这里为什么要加
                rightChild.addToPendingCount(1);
                // 将左右节点保存
                task.completionMap.put(leftChild, rightChild);

                // If task is not on the left spine
                if (task.leftPredecessor != null) {
                    /*
                     * Completion of left-predecessor, or left subtree,
                     * "happens-before" completion of left-most leaf node of
                     * right subtree.
                     * The left child's pending count needs to be updated before
                     * it is associated in the completion map, otherwise the
                     * left child can complete prematurely and violate the
                     * "happens-before" constraint.
                     * 计算左侧的结果
                     */
                    leftChild.addToPendingCount(1);
                    // Update association of left-predecessor to left-most
                    // leaf node of right subtree
                    // 同时满足 key  oldValue 且 替换newValue 成功才会返回true
                    if (task.completionMap.replace(task.leftPredecessor, task, leftChild)) {
                        // If replaced, adjust the pending count of the parent
                        // to complete when its children complete
                        task.addToPendingCount(-1);
                    } else {
                        // Left-predecessor has already completed, parent's
                        // pending count is adjusted by left-predecessor;
                        // left child is ready to complete
                        leftChild.addToPendingCount(-1);
                    }
                }

                ForEachOrderedTask<S, T> taskToFork;
                if (forkRight) {
                    forkRight = false;
                    rightSplit = leftSplit;
                    task = leftChild;
                    taskToFork = rightChild;
                }
                else {
                    forkRight = true;
                    task = rightChild;
                    taskToFork = leftChild;
                }
                taskToFork.fork();
            }

            /*
             * Task's pending count is either 0 or 1.  If 1 then the completion
             * map will contain a value that is task, and two calls to
             * tryComplete are required for completion, one below and one
             * triggered by the completion of task's left-predecessor in
             * onCompletion.  Therefore there is no data race within the if
             * block.
             * 代表有等待的任务
             */
            if (task.getPendingCount() > 0) {
                // Cannot complete just yet so buffer elements into a Node
                // for use when completion occurs
                @SuppressWarnings("unchecked")
                IntFunction<T[]> generator = size -> (T[]) new Object[size];
                // 构建指定长度的 node 对象
                Node.Builder<T> nb = task.helper.makeNodeBuilder(
                        task.helper.exactOutputSizeIfKnown(rightSplit),
                        generator);
                // 将数据拷贝到node 中
                task.node = task.helper.wrapAndCopyInto(nb, rightSplit).build();
                task.spliterator = null;
            }
            // 减少 pending
            task.tryComplete();
        }

        /**
         * caller 没被使用
         * @param caller the task invoking this method (which may
         * be this task itself)
         */
        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            if (node != null) {
                // Dump buffered elements from this leaf into the sink
                // 将节点中所有元素 都是用 action 进行处理
                node.forEach(action);
                node = null;
            }
            else if (spliterator != null) {
                // Dump elements output from this leaf's pipeline into the sink
                helper.wrapAndCopyInto(action, spliterator);
                spliterator = null;
            }

            // The completion of this task *and* the dumping of elements
            // "happens-before" completion of the associated left-most leaf task
            // of right subtree (if any, which can be this task's right sibling)
            // 传递方法
            //
            ForEachOrderedTask<S, T> leftDescendant = completionMap.remove(this);
            if (leftDescendant != null)
                leftDescendant.tryComplete();
        }
    }
}
