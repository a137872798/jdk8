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
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Abstract base class for "pipeline" classes, which are the core
 * implementations of the Stream interface and its primitive specializations.
 * Manages construction and evaluation of stream pipelines.
 *
 * <p>An {@code AbstractPipeline} represents an initial portion of a stream
 * pipeline, encapsulating a stream source and zero or more intermediate
 * operations.  The individual {@code AbstractPipeline} objects are often
 * referred to as <em>stages</em>, where each stage describes either the stream
 * source or an intermediate operation.
 *
 * <p>A concrete intermediate stage is generally built from an
 * {@code AbstractPipeline}, a shape-specific pipeline class which extends it
 * (e.g., {@code IntPipeline}) which is also abstract, and an operation-specific
 * concrete class which extends that.  {@code AbstractPipeline} contains most of
 * the mechanics of evaluating the pipeline, and implements methods that will be
 * used by the operation; the shape-specific classes add helper methods for
 * dealing with collection of results into the appropriate shape-specific
 * containers.
 *
 * <p>After chaining a new intermediate operation, or executing a terminal
 * operation, the stream is considered to be consumed, and no more intermediate
 * or terminal operations are permitted on this stream instance.
 *
 * @implNote
 * <p>For sequential streams, and parallel streams without
 * <a href="package-summary.html#StreamOps">stateful intermediate
 * operations</a>, parallel streams, pipeline evaluation is done in a single
 * pass that "jams" all the operations together.  For parallel streams with
 * stateful operations, execution is divided into segments, where each
 * stateful operations marks the end of a segment, and each segment is
 * evaluated separately and the result used as the input to the next
 * segment.  In all cases, the source data is not consumed until a terminal
 * operation begins.
 *
 * @param <E_IN>  type of input elements
 * @param <E_OUT> type of output elements
 * @param <S> type of the subclass implementing {@code BaseStream}
 * @since 1.8
 * 管道的骨架类
 * PipelineHelper 只是一个定义了 很多方法的接口
 * 每个 Pipeline 实际上更像一个节点 定义了  该步骤的操作逻辑
 */
abstract class AbstractPipeline<E_IN, E_OUT, S extends BaseStream<E_OUT, S>>
        extends PipelineHelper<E_OUT> implements BaseStream<E_OUT, S> {
    private static final String MSG_STREAM_LINKED = "stream has already been operated upon or closed";
    private static final String MSG_CONSUMED = "source already consumed or closed";

    /**
     * Backlink to the head of the pipeline chain (self if this is the source
     * stage).
     * 代表源头 也就是最上层的 pipeline  它连接在 Head 的后面
     */
    @SuppressWarnings("rawtypes")
    private final AbstractPipeline sourceStage;

    /**
     * The "upstream" pipeline, or null if this is the source stage.
     * 本 pipeline 的上一个 pipeline
     */
    @SuppressWarnings("rawtypes")
    private final AbstractPipeline previousStage;

    /**
     * The operation flags for the intermediate operation represented by this
     * pipeline object.
     * 代表本节点的 动作特性 比如是否是有状态 节点
     */
    protected final int sourceOrOpFlags;

    /**
     * The next stage in the pipeline, or null if this is the last stage.
     * Effectively final at the point of linking to the next pipeline.
     * 下一个节点 如果当前是 最后一个节点 就没有下个节点
     */
    @SuppressWarnings("rawtypes")
    private AbstractPipeline nextStage;

    /**
     * The number of intermediate operations between this pipeline object
     * and the stream source if sequential, or the previous stateful if parallel.
     * Valid at the point of pipeline preparation for evaluation.
     * 可以理解为 pipeline 的长度
     */
    private int depth;

    /**
     * The combined source and operation flags for the source and all operations
     * up to and including the operation represented by this pipeline object.
     * Valid at the point of pipeline preparation for evaluation.
     * 推测是从 source 开始 不断向下 并将 flag 整合起来
     */
    private int combinedFlags;

    /**
     * The source spliterator. Only valid for the head pipeline.
     * Before the pipeline is consumed if non-null then {@code sourceSupplier}
     * must be null. After the pipeline is consumed if non-null then is set to
     * null.
     * source 的  迭代器对象 只针对 headPipeline 起作用
     */
    private Spliterator<?> sourceSpliterator;

    /**
     * The source supplier. Only valid for the head pipeline. Before the
     * pipeline is consumed if non-null then {@code sourceSpliterator} must be
     * null. After the pipeline is consumed if non-null then is set to null.
     * 生成 数据流的 提供者对象 只针对 headPipeline 起作用
     */
    private Supplier<? extends Spliterator<?>> sourceSupplier;

    /**
     * True if this pipeline has been linked or consumed
     * 判断是否 被消费 或者 连接
     */
    private boolean linkedOrConsumed;

    /**
     * True if there are any stateful ops in the pipeline; only valid for the
     * source stage.
     * 如果 任何一个 环节是有状态的返回true  什么是有状态的操作???
     */
    private boolean sourceAnyStateful;

    /**
     * 当source被关闭时触发
     */
    private Runnable sourceCloseAction;

    /**
     * True if pipeline is parallel, otherwise the pipeline is sequential; only
     * valid for the source stage.
     * 判断该流是否并行
     */
    private boolean parallel;

    /**
     * Constructor for the head of a stream pipeline.
     *
     * @param source {@code Supplier<Spliterator>} describing the stream source
     * @param sourceFlags The source flags for the stream source, described in
     * {@link StreamOpFlag}
     * @param parallel True if the pipeline is parallel
     *                 初始化 管道对象
     */
    AbstractPipeline(Supplier<? extends Spliterator<?>> source,
                     int sourceFlags, boolean parallel) {
        // 默认 没有前个节点
        this.previousStage = null;
        // 使用传入的函数对象生成数据源
        this.sourceSupplier = source;
        // 将本对象 作为源头
        this.sourceStage = this;
        // 传入 对应的 数据源特性 并生成flag
        this.sourceOrOpFlags = sourceFlags & StreamOpFlag.STREAM_MASK;
        // The following is an optimization of:
        // StreamOpFlag.combineOpFlags(sourceOrOpFlags, StreamOpFlag.INITIAL_OPS_VALUE);
        this.combinedFlags = (~(sourceOrOpFlags << 1)) & StreamOpFlag.INITIAL_OPS_VALUE;
        // 默认深度为0
        this.depth = 0;
        this.parallel = parallel;
    }

    /**
     * Constructor for the head of a stream pipeline.
     *
     * @param source {@code Spliterator} describing the stream source
     * @param sourceFlags the source flags for the stream source, described in
     * {@link StreamOpFlag}
     * @param parallel {@code true} if the pipeline is parallel
     *                             这里使用迭代器进行初始化   (可拆分迭代器)
     */
    AbstractPipeline(Spliterator<?> source,
                     int sourceFlags, boolean parallel) {
        this.previousStage = null;
        this.sourceSpliterator = source;
        this.sourceStage = this;
        this.sourceOrOpFlags = sourceFlags & StreamOpFlag.STREAM_MASK;
        // The following is an optimization of:
        // StreamOpFlag.combineOpFlags(sourceOrOpFlags, StreamOpFlag.INITIAL_OPS_VALUE);
        this.combinedFlags = (~(sourceOrOpFlags << 1)) & StreamOpFlag.INITIAL_OPS_VALUE;
        this.depth = 0;
        this.parallel = parallel;
    }

    /**
     * Constructor for appending an intermediate operation stage onto an
     * existing pipeline.
     *
     * @param previousStage the upstream pipeline stage
     * @param opFlags the operation flags for the new stage, described in
     * {@link StreamOpFlag}
     *                通过 传入 前一个 pipeline 进行初始化
     */
    AbstractPipeline(AbstractPipeline<?, E_IN, ?> previousStage, int opFlags) {
        // 如果 前一个 pipeline 已经 连接完毕（禁止连接新节点） 或者已经被消费了 就 抛出异常
        // 消费 应该就是 计算出结果 了 而 连接 则代表 一个环节不能连接多个动作 不同于 CompletableFuture 这个对象 在一个对象上可以关联多个动作
        if (previousStage.linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);

        // 将上个元素设置为已连接
        previousStage.linkedOrConsumed = true;
        // 将上个节点指向自己
        previousStage.nextStage = this;

        this.previousStage = previousStage;
        // OP 代表 该节点是作为一个 动作 而不是源头
        this.sourceOrOpFlags = opFlags & StreamOpFlag.OP_MASK;
        this.combinedFlags = StreamOpFlag.combineOpFlags(opFlags, previousStage.combinedFlags);
        // 通过这种方式来传入 source 指针
        this.sourceStage = previousStage.sourceStage;
        // 判断当前pipeline 是否是有状态的
        if (opIsStateful())
            sourceStage.sourceAnyStateful = true;
        this.depth = previousStage.depth + 1;
    }


    // Terminal evaluation methods

    /**
     * Evaluate the pipeline with a terminal operation to produce a result.
     *
     * @param <R> the type of result
     * @param terminalOp the terminal operation to be applied to the pipeline.
     * @return the result
     * 通过一个终止操作 计算出 本 pipeline 的结果
     */
    final <R> R evaluate(TerminalOp<E_OUT, R> terminalOp) {
        // 确保 输入输出是同一种
        assert getOutputShape() == terminalOp.inputShape();
        // 已消费情况是不能处理的
        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        linkedOrConsumed = true;

        // 判断是否是并行 走不同逻辑   将自身作为参数传入 此时本对象可以直接获取到 source
        return isParallel()
                // 代表并行执行
               ? terminalOp.evaluateParallel(this, sourceSpliterator(terminalOp.getOpFlags()))
                // 代表串行执行
               : terminalOp.evaluateSequential(this, sourceSpliterator(terminalOp.getOpFlags()));
    }

    /**
     * Collect the elements output from the pipeline stage.
     *
     * @param generator the array generator to be used to create array instances
     * @return a flat array-backed Node that holds the collected output elements
     * 将输出 结果 整合成一个 node 对象 该方法应该就是对应.collect() 等
     */
    @SuppressWarnings("unchecked")
    final Node<E_OUT> evaluateToArrayNode(IntFunction<E_OUT[]> generator) {
        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        linkedOrConsumed = true;

        // If the last intermediate operation is stateful then
        // evaluate directly to avoid an extra collection step
        // 如果是并行 且 该元素不是 source 且携带状态
        if (isParallel() && previousStage != null && opIsStateful()) {
            // Set the depth of this, last, pipeline stage to zero to slice the
            // pipeline such that this operation will not be included in the
            // upstream slice and upstream operations will not be included
            // in this slice
            // 将本对象的深度置0 了  应该是从本对象开始 开启了一个新线程并处理任务
            depth = 0;
            // 并行处理
            return opEvaluateParallel(previousStage, previousStage.sourceSpliterator(0), generator);
        }
        else {
            // 串行执行
            return evaluate(sourceSpliterator(0), true, generator);
        }
    }

    /**
     * Gets the source stage spliterator if this pipeline stage is the source
     * stage.  The pipeline is consumed after this method is called and
     * returns successfully.
     *
     * @return the source stage spliterator
     * @throws IllegalStateException if this pipeline stage is not the source
     *         stage.
     *         将head 中获取 迭代器对象 或者 使用携带的提供者对象来创建
     */
    @SuppressWarnings("unchecked")
    final Spliterator<E_OUT> sourceStageSpliterator() {
        if (this != sourceStage)
            throw new IllegalStateException();

        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        // 应该是代表被消耗掉了 这样下游就不能去 连接他了
        linkedOrConsumed = true;

        /**
         * 如果source 本身就包含迭代器 就直接返回
         */
        if (sourceStage.sourceSpliterator != null) {
            @SuppressWarnings("unchecked")
            Spliterator<E_OUT> s = sourceStage.sourceSpliterator;
            sourceStage.sourceSpliterator = null;
            return s;
        }
        // 如果迭代器提供者存在 就返回 生成的迭代器
        else if (sourceStage.sourceSupplier != null) {
            @SuppressWarnings("unchecked")
            Spliterator<E_OUT> s = (Spliterator<E_OUT>) sourceStage.sourceSupplier.get();
            sourceStage.sourceSupplier = null;
            return s;
        }
        else {
            throw new IllegalStateException(MSG_CONSUMED);
        }
    }

    // BaseStream

    /**
     * 将本数据流变成串行
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public final S sequential() {
        sourceStage.parallel = false;
        return (S) this;
    }

    /**
     * 设置成并行
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public final S parallel() {
        sourceStage.parallel = true;
        return (S) this;
    }

    /**
     * 关闭该对象
     */
    @Override
    public void close() {
        linkedOrConsumed = true;
        sourceSupplier = null;
        sourceSpliterator = null;
        if (sourceStage.sourceCloseAction != null) {
            Runnable closeAction = sourceStage.sourceCloseAction;
            sourceStage.sourceCloseAction = null;
            closeAction.run();
        }
    }

    /**
     * 设置关闭钩子
     * @param closeHandler A task to execute when the stream is closed
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public S onClose(Runnable closeHandler) {
        Runnable existingHandler = sourceStage.sourceCloseAction;
        sourceStage.sourceCloseAction =
                (existingHandler == null)
                ? closeHandler
                        // 组合多个函数对象
                : Streams.composeWithExceptions(existingHandler, closeHandler);
        return (S) this;
    }

    // Primitive specialization use co-variant overrides, hence is not final

    /**
     * 返回 迭代器对象  该方法不同于上面的  sourceStageSpliterator  如果本节点就是source 则一致
     * 如果传入的是下面的某个节点
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public Spliterator<E_OUT> spliterator() {
        if (linkedOrConsumed)
            throw new IllegalStateException(MSG_STREAM_LINKED);
        linkedOrConsumed = true;

        // 如果本对象就是源头对象直接返回 对应属性
        if (this == sourceStage) {
            if (sourceStage.sourceSpliterator != null) {
                @SuppressWarnings("unchecked")
                Spliterator<E_OUT> s = (Spliterator<E_OUT>) sourceStage.sourceSpliterator;
                sourceStage.sourceSpliterator = null;
                return s;
            }
            else if (sourceStage.sourceSupplier != null) {
                @SuppressWarnings("unchecked")
                Supplier<Spliterator<E_OUT>> s = (Supplier<Spliterator<E_OUT>>) sourceStage.sourceSupplier;
                sourceStage.sourceSupplier = null;
                // 使用一个 包装对象来实现 惰性加载 只有真正调用 split的方法时  才执行 supplier 的 get() 方法
                return lazySpliterator(s);
            }
            else {
                throw new IllegalStateException(MSG_CONSUMED);
            }
        }
        // 代表非head 节点
        else {
            // 将 sourceSpliterator 方法 封装成 获取迭代器的函数 加上本对象 作为一个迭代器
            return wrap(this, () -> sourceSpliterator(0), isParallel());
        }
    }

    /**
     * 返回是否是并行
     * @return
     */
    @Override
    public final boolean isParallel() {
        return sourceStage.parallel;
    }


    /**
     * Returns the composition of stream flags of the stream source and all
     * intermediate operations.
     *
     * @return the composition of stream flags of the stream source and all
     *         intermediate operations
     * @see StreamOpFlag
     */
    final int getStreamFlags() {
        return StreamOpFlag.toStreamFlags(combinedFlags);
    }

    /**
     * Get the source spliterator for this pipeline stage.  For a sequential or
     * stateless parallel pipeline, this is the source spliterator.  For a
     * stateful parallel pipeline, this is a spliterator describing the results
     * of all computations up to and including the most recent stateful
     * operation.
     * 传入一个 flag 并返回一个 可拆分迭代器对象
     */
    @SuppressWarnings("unchecked")
    private Spliterator<?> sourceSpliterator(int terminalFlags) {
        // Get the source spliterator of the pipeline
        Spliterator<?> spliterator = null;
        // 先加载 source 的迭代器
        if (sourceStage.sourceSpliterator != null) {
            spliterator = sourceStage.sourceSpliterator;
            sourceStage.sourceSpliterator = null;
        }
        else if (sourceStage.sourceSupplier != null) {
            spliterator = (Spliterator<?>) sourceStage.sourceSupplier.get();
            sourceStage.sourceSupplier = null;
        }
        else {
            throw new IllegalStateException(MSG_CONSUMED);
        }

        // 如果是并行  如果数据源是有状态的 有状态 代表 必须将之前的动作执行完才能继续往下走
        // 比如 limit() skip()
        if (isParallel() && sourceStage.sourceAnyStateful) {
            // Adapt the source spliterator, evaluating each stateful op
            // in the pipeline up to and including this pipeline stage.
            // The depth and flags of each pipeline stage are adjusted accordingly.
            // 将深度设置为1
            int depth = 1;
            // 下面这个循环的意思是 从source 开始迭代 直到 走到本元素
            for (@SuppressWarnings("rawtypes") AbstractPipeline u = sourceStage, p = sourceStage.nextStage, e = this;
                 u != e;
                 u = p, p = p.nextStage) {

                int thisOpFlags = p.sourceOrOpFlags;
                // p 一般情况下是下个节点  如果当前节点是最后一个节点 那么 next节点就是自身
                if (p.opIsStateful()) {
                    // 将深度置0
                    depth = 0;

                    // 下个节点是否是 短路动作 是就去掉
                    if (StreamOpFlag.SHORT_CIRCUIT.isKnown(thisOpFlags)) {
                        // Clear the short circuit flag for next pipeline stage
                        // This stage encapsulates short-circuiting, the next
                        // stage may not have any short-circuit operations, and
                        // if so spliterator.forEachRemaining should be used
                        // for traversal
                        thisOpFlags = thisOpFlags & ~StreamOpFlag.IS_SHORT_CIRCUIT;
                    }

                    // 使用下个对象 将本对象 和 source迭代器包装
                    spliterator = p.opEvaluateParallelLazy(u, spliterator);

                    // Inject or clear SIZED on the source pipeline stage
                    // based on the stage's spliterator
                    thisOpFlags = spliterator.hasCharacteristics(Spliterator.SIZED)
                            ? (thisOpFlags & ~StreamOpFlag.NOT_SIZED) | StreamOpFlag.IS_SIZED
                            : (thisOpFlags & ~StreamOpFlag.IS_SIZED) | StreamOpFlag.NOT_SIZED;
                }
                // 设置该节点的深度 如果发现某节点是 stateful 就会重置深度
                p.depth = depth++;
                p.combinedFlags = StreamOpFlag.combineOpFlags(thisOpFlags, u.combinedFlags);
            }
        }

        if (terminalFlags != 0)  {
            // Apply flags from the terminal operation to last pipeline stage
            combinedFlags = StreamOpFlag.combineOpFlags(terminalFlags, combinedFlags);
        }

        return spliterator;
    }

    // PipelineHelper

    /**
     * 看来只有 source 可以返回 outputShape()
     * @return
     */
    @Override
    final StreamShape getSourceShape() {
        @SuppressWarnings("rawtypes")
        AbstractPipeline p = AbstractPipeline.this;
        while (p.depth > 0) {
            p = p.previousStage;
        }
        return p.getOutputShape();
    }

    /**
     * 返回精准的长度信息
     * @param spliterator the spliterator describing the relevant portion of the
     *        source data
     * @param <P_IN>
     * @return
     */
    @Override
    final <P_IN> long exactOutputSizeIfKnown(Spliterator<P_IN> spliterator) {
        // 如果携带了 SIZED 标识 才代表 可以调用某方法获取精确的size信息
        return StreamOpFlag.SIZED.isKnown(getStreamAndOpFlags()) ? spliterator.getExactSizeIfKnown() : -1;
    }

    /**
     * 包装sink 拷贝信息
     * @param sink the {@code Sink} to receive the results
     * @param spliterator the spliterator describing the source input to process
     * @param <P_IN>
     * @param <S>
     * @return
     */
    @Override
    final <P_IN, S extends Sink<E_OUT>> S wrapAndCopyInto(S sink, Spliterator<P_IN> spliterator) {
        copyInto(wrapSink(Objects.requireNonNull(sink)), spliterator);
        return sink;
    }

    @Override
    final <P_IN> void copyInto(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator) {
        Objects.requireNonNull(wrappedSink);

        /**
         * 没有短路的情况 使用指定长度 初始化 sink容器 sink 默认实现是Node对象
         *
         */
        if (!StreamOpFlag.SHORT_CIRCUIT.isKnown(getStreamAndOpFlags())) {
            wrappedSink.begin(spliterator.getExactSizeIfKnown());
            // 将元素 填充到容器中
            spliterator.forEachRemaining(wrappedSink);
            wrappedSink.end();
        }
        else {
            // 如果发生了短路
            copyIntoWithCancel(wrappedSink, spliterator);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> void copyIntoWithCancel(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator) {
        @SuppressWarnings({"rawtypes","unchecked"})
        AbstractPipeline p = AbstractPipeline.this;
        while (p.depth > 0) {
            p = p.previousStage;
        }
        // 初始化内部的数组
        wrappedSink.begin(spliterator.getExactSizeIfKnown());
        p.forEachWithCancel(spliterator, wrappedSink);
        wrappedSink.end();
    }

    @Override
    final int getStreamAndOpFlags() {
        return combinedFlags;
    }

    final boolean isOrdered() {
        return StreamOpFlag.ORDERED.isKnown(combinedFlags);
    }

    /**
     * 包装 sink
     * @param sink the {@code Sink} to receive the results
     * @param <P_IN>
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> Sink<P_IN> wrapSink(Sink<E_OUT> sink) {
        Objects.requireNonNull(sink);

        for ( @SuppressWarnings("rawtypes") AbstractPipeline p=AbstractPipeline.this; p.depth > 0; p=p.previousStage) {
            // 使用 combinedFlag 去 包装信息
            sink = p.opWrapSink(p.previousStage.combinedFlags, sink);
        }
        return (Sink<P_IN>) sink;
    }

    /**
     * 包装迭代器
     * @param sourceSpliterator
     * @param <P_IN>
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> Spliterator<E_OUT> wrapSpliterator(Spliterator<P_IN> sourceSpliterator) {
        if (depth == 0) {
            return (Spliterator<E_OUT>) sourceSpliterator;
        }
        else {
            return wrap(this, () -> sourceSpliterator, isParallel());
        }
    }

    /**
     * 计算结果
     * @param spliterator the source {@code Spliterator}
     * @param flatten if true and the pipeline is a parallel pipeline then the
     *        {@code Node} returned will contain no children, otherwise the
     *        {@code Node} may represent the root in a tree that reflects the
     *        shape of the computation tree.
     * @param generator a factory function for array instances
     * @param <P_IN>
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    final <P_IN> Node<E_OUT> evaluate(Spliterator<P_IN> spliterator,
                                      boolean flatten,
                                      IntFunction<E_OUT[]> generator) {
        if (isParallel()) {
            // @@@ Optimize if op of this pipeline stage is a stateful op
            return evaluateToNode(this, spliterator, flatten, generator);
        }
        else {
            // 生成 builder 对象
            Node.Builder<E_OUT> nb = makeNodeBuilder(
                    exactOutputSizeIfKnown(spliterator), generator);
            // 包装builder 对象
            return wrapAndCopyInto(nb, spliterator).build();
        }
    }


    // Shape-specific abstract methods, implemented by XxxPipeline classes

    /**
     * Get the output shape of the pipeline.  If the pipeline is the head,
     * then it's output shape corresponds to the shape of the source.
     * Otherwise, it's output shape corresponds to the output shape of the
     * associated operation.
     *
     * @return the output shape
     */
    abstract StreamShape getOutputShape();

    /**
     * Collect elements output from a pipeline into a Node that holds elements
     * of this shape.
     *
     * @param helper the pipeline helper describing the pipeline stages
     * @param spliterator the source spliterator
     * @param flattenTree true if the returned node should be flattened
     * @param generator the array generator
     * @return a Node holding the output of the pipeline
     */
    abstract <P_IN> Node<E_OUT> evaluateToNode(PipelineHelper<E_OUT> helper,
                                               Spliterator<P_IN> spliterator,
                                               boolean flattenTree,
                                               IntFunction<E_OUT[]> generator);

    /**
     * Create a spliterator that wraps a source spliterator, compatible with
     * this stream shape, and operations associated with a {@link
     * PipelineHelper}.
     *
     * @param ph the pipeline helper describing the pipeline stages
     * @param supplier the supplier of a spliterator
     * @return a wrapping spliterator compatible with this shape
     */
    abstract <P_IN> Spliterator<E_OUT> wrap(PipelineHelper<E_OUT> ph,
                                            Supplier<Spliterator<P_IN>> supplier,
                                            boolean isParallel);

    /**
     * Create a lazy spliterator that wraps and obtains the supplied the
     * spliterator when a method is invoked on the lazy spliterator.
     * @param supplier the supplier of a spliterator
     */
    abstract Spliterator<E_OUT> lazySpliterator(Supplier<? extends Spliterator<E_OUT>> supplier);

    /**
     * Traverse the elements of a spliterator compatible with this stream shape,
     * pushing those elements into a sink.   If the sink requests cancellation,
     * no further elements will be pulled or pushed.
     *
     * @param spliterator the spliterator to pull elements from
     * @param sink the sink to push elements to
     */
    abstract void forEachWithCancel(Spliterator<E_OUT> spliterator, Sink<E_OUT> sink);

    /**
     * Make a node builder compatible with this stream shape.
     *
     * @param exactSizeIfKnown if {@literal >=0}, then a node builder will be
     * created that has a fixed capacity of at most sizeIfKnown elements. If
     * {@literal < 0}, then the node builder has an unfixed capacity. A fixed
     * capacity node builder will throw exceptions if an element is added after
     * builder has reached capacity, or is built before the builder has reached
     * capacity.
     *
     * @param generator the array generator to be used to create instances of a
     * T[] array. For implementations supporting primitive nodes, this parameter
     * may be ignored.
     * @return a node builder
     */
    @Override
    abstract Node.Builder<E_OUT> makeNodeBuilder(long exactSizeIfKnown,
                                                 IntFunction<E_OUT[]> generator);


    // Op-specific abstract methods, implemented by the operation class

    /**
     * Returns whether this operation is stateful or not.  If it is stateful,
     * then the method
     * {@link #opEvaluateParallel(PipelineHelper, java.util.Spliterator, java.util.function.IntFunction)}
     * must be overridden.
     *
     * @return {@code true} if this operation is stateful
     */
    abstract boolean opIsStateful();

    /**
     * Accepts a {@code Sink} which will receive the results of this operation,
     * and return a {@code Sink} which accepts elements of the input type of
     * this operation and which performs the operation, passing the results to
     * the provided {@code Sink}.
     *
     * @apiNote
     * The implementation may use the {@code flags} parameter to optimize the
     * sink wrapping.  For example, if the input is already {@code DISTINCT},
     * the implementation for the {@code Stream#distinct()} method could just
     * return the sink it was passed.
     *
     * @param flags The combined stream and operation flags up to, but not
     *        including, this operation
     * @param sink sink to which elements should be sent after processing
     * @return a sink which accepts elements, perform the operation upon
     *         each element, and passes the results (if any) to the provided
     *         {@code Sink}.
     */
    abstract Sink<E_IN> opWrapSink(int flags, Sink<E_OUT> sink);

    /**
     * Performs a parallel evaluation of the operation using the specified
     * {@code PipelineHelper} which describes the upstream intermediate
     * operations.  Only called on stateful operations.  If {@link
     * #opIsStateful()} returns true then implementations must override the
     * default implementation.
     *
     * @implSpec The default implementation always throw
     * {@code UnsupportedOperationException}.
     *
     * @param helper the pipeline helper describing the pipeline stages
     * @param spliterator the source {@code Spliterator}
     * @param generator the array generator
     * @return a {@code Node} describing the result of the evaluation
     */
    <P_IN> Node<E_OUT> opEvaluateParallel(PipelineHelper<E_OUT> helper,
                                          Spliterator<P_IN> spliterator,
                                          IntFunction<E_OUT[]> generator) {
        throw new UnsupportedOperationException("Parallel evaluation is not supported");
    }

    /**
     * Returns a {@code Spliterator} describing a parallel evaluation of the
     * operation, using the specified {@code PipelineHelper} which describes the
     * upstream intermediate operations.  Only called on stateful operations.
     * It is not necessary (though acceptable) to do a full computation of the
     * result here; it is preferable, if possible, to describe the result via a
     * lazily evaluated spliterator.
     *
     * @implSpec The default implementation behaves as if:
     * <pre>{@code
     *     return evaluateParallel(helper, i -> (E_OUT[]) new
     * Object[i]).spliterator();
     * }</pre>
     * and is suitable for implementations that cannot do better than a full
     * synchronous evaluation.
     *
     * @param helper the pipeline helper
     * @param spliterator the source {@code Spliterator}
     * @return a {@code Spliterator} describing the result of the evaluation
     */
    @SuppressWarnings("unchecked")
    <P_IN> Spliterator<E_OUT> opEvaluateParallelLazy(PipelineHelper<E_OUT> helper,
                                                     Spliterator<P_IN> spliterator) {
        return opEvaluateParallel(helper, spliterator, i -> (E_OUT[]) new Object[i]).spliterator();
    }
}
