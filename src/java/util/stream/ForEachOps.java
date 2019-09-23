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
 * 针对 forEach 的操作   该对象是一个 TerminalOp 对象
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
         * 串行操作 将source 中的数据 通过 sink 链进行处理 之后传递到底部
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
         * 注意这里还没有对 sink 进行加工
         * @param helper the pipeline helper
         * @param spliterator the source spliterator
         * @param <S>
         * @return
         */
        @Override
        public <S> Void evaluateParallel(PipelineHelper<T> helper,
                                         Spliterator<S> spliterator) {

            // 判断该并行对象 是否有序  传入本对象 作为 sink 对象  注意这里的sink 对象没有被包装
            if (ordered)
                new ForEachOrderedTask<>(helper, spliterator, this).invoke();
            else
                // 先将本sink 对象通过 每个环节进行包装 之后构建task 对象并执行
                // invoke 方法 会在外部线程直接执行 任务 如果任务没有完成 则将剩余任务设置到 FJPool.WorkQueue 中
                new ForEachTask<>(helper, spliterator, helper.wrapSink(this)).invoke();
            return null;
        }

        // TerminalSink

        /**
         * forEach 操作调用 get 返回null
         * @return
         */
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
             * 使用设置的消费者 处理 每个传递下来的元素
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
     * 无序状态下的并行处理任务 该sink 对象已经包含了整个处理链了   该对象本身 继承自 ForkJoinTask 的 CC 对象 该对象 doExec 总是返回false 且一旦被执行会将 关联的 其他CC 优先执行
     * (在 FJPool 中任务会被不断的拆分)
     * @param <S>
     * @param <T>
     */
    @SuppressWarnings("serial")
    static final class ForEachTask<S, T> extends CountedCompleter<Void> {
        /**
         * 内部包含了source 数据
         */
        private Spliterator<S> spliterator;
        /**
         * 处理上游数据并设置 结果的 对象(实际上是一个链式对象)
         */
        private final Sink<S> sink;

        private final PipelineHelper<T> helper;
        /**
         * 目标长度
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

        /**
         * 使用 父节点进行初始化
         * @param parent 父节点对象
         * @param spliterator 当前的数据源 一般是从parent 中分裂出来的 只有一部分数据
         */
        ForEachTask(ForEachTask<S, T> parent, Spliterator<S> spliterator) {
            // 对应到上层的 completer 节点
            super(parent);
            this.spliterator = spliterator;
            // sink 对象本身会被不断的继承 且 sink 本身已经是一个加工完的处理链了
            this.sink = parent.sink;
            // 这个值 代表拆分的 阈值 小于这个值就不再拆分了
            this.targetSize = parent.targetSize;
            // 主要是为了获取 stream 的标识
            this.helper = parent.helper;
        }

        // Similar to AbstractTask but doesn't need to track child tasks
        // invoke() 方法 首先在外部线程执行该任务
        public void compute() {
            Spliterator<S> rightSplit = spliterator, leftSplit;
            // 获取source 的总长度
            long sizeEstimate = rightSplit.estimateSize(), sizeThreshold;
            // 一般情况 下 初始化 targetSize 就是0
            if ((sizeThreshold = targetSize) == 0L)
                // 获取一个 拆分的 阈值 可能是 小于这个值就不再拆分了
                targetSize = sizeThreshold = AbstractTask.suggestTargetSize(sizeEstimate);
            // 判断是否包含短路的逻辑
            boolean isShortCircuit = StreamOpFlag.SHORT_CIRCUIT.isKnown(helper.getStreamAndOpFlags());
            // 是否拆分右侧
            boolean forkRight = false;
            // 该对象是整个处理链的具象化体现  source 中每个元素经过它过滤后剩余的 就是 stream 返回的结果
            Sink<S> taskSink = sink;
            ForEachTask<S, T> task = this;
            // 没有短路 且能 接受请求时  短路 就对应 anyMatch() 之类的方法
            while (!isShortCircuit || !taskSink.cancellationRequested()) {
                // 下面的代码 实际上就是 FJTask 的标准模板

                // 代表不能再拆分了
                if (sizeEstimate <= sizeThreshold ||
                        // 或者拆分的结果为null
                    (leftSplit = rightSplit.trySplit()) == null) {
                    // 当右侧的任务无法再拆分时 就进行真正的过滤逻辑
                    task.helper.copyInto(taskSink, rightSplit);
                    break;
                }

                // 这里就是对应 根据数据大小拆分任务的逻辑  为什么以这种方式拆分就不细看了 核心就是将大块任务拆分后设置到 FJPool 中
                // 当任务无法拆分时 就会从 source 中将数据填充到 sink中(需要经过一连串的过滤链) 但是这里很可能是无序的  针对要求有序的 任务不能按这种方式拆分

                // 初始化一个 左节点对象 并以 task 作为 父节点  因为 leftSplit = rightSplit.trySplit() 使得 leftSplit 已经是分裂过的数据了
                // 每次的 leftTask 都是上层拆分出来的
                ForEachTask<S, T> leftTask = new ForEachTask<>(task, leftSplit);
                // 增加父节点的悬挂节点  一般每个节点都会悬挂2个
                task.addToPendingCount(1);
                ForEachTask<S, T> taskToFork;
                if (forkRight) {
                    forkRight = false;
                    // 下次针对左节点进行拆分
                    rightSplit = leftSplit;
                    taskToFork = task;
                    task = leftTask;
                }
                // 首次拆分进入下面 代表本次插入FJPool 的是左侧的任务
                else {
                    // 下次会插入右侧的任务
                    forkRight = true;
                    taskToFork = leftTask;
                }
                // 将左侧任务 也就是从 父节点拆分出来的任务添加到线程池中 (左节点会在后台线程继续执行 compute 逻辑)
                taskToFork.fork();
                sizeEstimate = rightSplit.estimateSize();
            }
            task.spliterator = null;
            // 减少一个悬挂任务 因为本线程退出就意味着 本悬挂任务已经完成 （还有很多后台任务在线程池中处理）
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
         * 就是为了保证有序执行
         */

        /**
         * 该对象是用来获取长度信息的 便于计算拆分阈值
         */
        private final PipelineHelper<T> helper;
        /**
         * 存放source 数据的对象
         */
        private Spliterator<S> spliterator;
        /**
         * 拆分阈值
         */
        private final long targetSize;
        /**
         * 这里使用了一个额外的容器
         */
        private final ConcurrentHashMap<ForEachOrderedTask<S, T>, ForEachOrderedTask<S, T>> completionMap;
        /**
         * 未包装的 sink 对象 accept() 通过使用一个 consumer 处理数据
         */
        private final Sink<T> action;
        /**
         * 左前置节点
         */
        private final ForEachOrderedTask<S, T> leftPredecessor;
        private Node<T> node;

        /**
         * 初始化 顺序任务对象
         * @param helper
         * @param spliterator
         * @param action
         */
        protected ForEachOrderedTask(PipelineHelper<T> helper,
                                     Spliterator<S> spliterator,
                                     Sink<T> action) {
            // 父类的 complete 对象为null
            super(null);
            this.helper = helper;
            this.spliterator = spliterator;
            this.targetSize = AbstractTask.suggestTargetSize(spliterator.estimateSize());
            // Size map to avoid concurrent re-sizes
            this.completionMap = new ConcurrentHashMap<>(Math.max(16, AbstractTask.LEAF_TARGET << 1));
            this.action = action;
            this.leftPredecessor = null;
        }

        /**
         * 通过一个 父节点进行初始化
         * @param parent
         * @param spliterator
         * @param leftPredecessor 指定的左前置节点
         */
        ForEachOrderedTask(ForEachOrderedTask<S, T> parent,
                           Spliterator<S> spliterator,
                           ForEachOrderedTask<S, T> leftPredecessor) {
            // 设置父类的 complete
            super(parent);
            this.helper = parent.helper;
            this.spliterator = spliterator;
            this.targetSize = parent.targetSize;
            // 看来子节点都是使用同一个并发容器
            this.completionMap = parent.completionMap;
            // 共用同一个action
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
                // 代表在完成父节点前必须先完成 左右节点
                task.addToPendingCount(1);
                // Completion of the left child "happens-before" completion of
                // the right child
                // 完成右节点前必须先完成 左节点
                rightChild.addToPendingCount(1);
                // 将左右节点保存  这样可以通过 left 定位到 right
                task.completionMap.put(leftChild, rightChild);

                // If task is not on the left spine
                // 如果 存在 前置左节点 那么该任务现在还不能执行
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
                task.node = task.helper.wrapAndCopyInto(nb, rightSplit).build();
                task.spliterator = null;
            }
            // 当本任务完成时触发 如果该对象上的悬挂节点 全部执行完后 触发 onCompletion
            // 否则 将悬挂数 -1
            task.tryComplete();
        }

        /**
         * 这里不细看了 实际上 即使是顺序处理 每个节点 也是使用了wrap 后的 sink 进行数据传递的
         * @param caller the task invoking this method (which may
         * be this task itself)
         */
        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            if (node != null) {
                // Dump buffered elements from this leaf into the sink
                // 将节点中所有元素 使用action 去处理
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
