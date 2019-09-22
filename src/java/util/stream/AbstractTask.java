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

import java.util.Spliterator;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;

/**
 * Abstract base class for most fork-join tasks used to implement stream ops.
 * Manages splitting logic, tracking of child tasks, and intermediate results.
 * Each task is associated with a {@link Spliterator} that describes the portion
 * of the input associated with the subtree rooted at this task.
 * Tasks may be leaf nodes (which will traverse the elements of
 * the {@code Spliterator}) or internal nodes (which split the
 * {@code Spliterator} into multiple child tasks).
 *
 * @implNote
 * <p>This class is based on {@link CountedCompleter}, a form of fork-join task
 * where each task has a semaphore-like count of uncompleted children, and the
 * task is implicitly completed and notified when its last child completes.
 * Internal node tasks will likely override the {@code onCompletion} method from
 * {@code CountedCompleter} to merge the results from child tasks into the
 * current task's result.
 *
 * <p>Splitting and setting up the child task links is done by {@code compute()}
 * for internal nodes.  At {@code compute()} time for leaf nodes, it is
 * guaranteed that the parent's child-related fields (including sibling links
 * for the parent's children) will be set up for all children.
 *
 * <p>For example, a task that performs a reduce would override {@code doLeaf()}
 * to perform a reduction on that leaf node's chunk using the
 * {@code Spliterator}, and override {@code onCompletion()} to merge the results
 * of the child tasks for internal nodes:
 *
 * <pre>{@code
 *     protected S doLeaf() {
 *         spliterator.forEach(...);
 *         return localReductionResult;
 *     }
 *
 *     public void onCompletion(CountedCompleter caller) {
 *         if (!isLeaf()) {
 *             ReduceTask<P_IN, P_OUT, T, R> child = children;
 *             R result = child.getLocalResult();
 *             child = child.nextSibling;
 *             for (; child != null; child = child.nextSibling)
 *                 result = combine(result, child.getLocalResult());
 *             setLocalResult(result);
 *         }
 *     }
 * }</pre>
 *
 * <p>Serialization is not supported as there is no intention to serialize
 * tasks managed by stream ops.
 *
 * @param <P_IN> Type of elements input to the pipeline
 * @param <P_OUT> Type of elements output from the pipeline
 * @param <R> Type of intermediate result, which may be different from operation
 *        result type
 * @param <K> Type of parent, child and sibling tasks
 * @since 1.8
 * Stream 中使用的task 是 基于 ForkJoinTask  的  CC 对象  该对象 执行doExec 总是返回 false
 */
@SuppressWarnings("serial")
abstract class AbstractTask<P_IN, P_OUT, R,
                            K extends AbstractTask<P_IN, P_OUT, R, K>>
        extends CountedCompleter<R> {

    /**
     * Default target factor of leaf tasks for parallel decomposition.
     * To allow load balancing, we over-partition, currently to approximately
     * four tasks per processor, which enables others to help out
     * if leaf tasks are uneven or some processors are otherwise busy.
     */
    static final int LEAF_TARGET = ForkJoinPool.getCommonPoolParallelism() << 2;

    /** The pipeline helper, common to all tasks in a computation */
    /**
     * pipeline 实现类 (helper 只是一个接口) 用于 协助 并发执行任务
     */
    protected final PipelineHelper<P_OUT> helper;

    /**
     * The spliterator for the portion of the input associated with the subtree
     * rooted at this task
     * 拆分迭代器
     */
    protected Spliterator<P_IN> spliterator;

    /** Target leaf size, common to all tasks in a computation */
    /**
     * 目标长度
     */
    protected long targetSize; // may be laziliy initialized

    /**
     * The left child.
     * null if no children
     * if non-null rightChild is non-null
     * 左子节点
     */
    protected K leftChild;

    /**
     * The right child.
     * null if no children
     * if non-null leftChild is non-null
     * 右子节点
     */
    protected K rightChild;

    /** The result of this node, if completed */
    /**
     * 执行结果
     */
    private R localResult;

    /**
     * Constructor for root nodes.
     *
     * @param helper The {@code PipelineHelper} describing the stream pipeline
     *               up to this operation
     * @param spliterator The {@code Spliterator} describing the source for this
     *                    pipeline
     */
    protected AbstractTask(PipelineHelper<P_OUT> helper,
                           Spliterator<P_IN> spliterator) {
        // 代表初始化该对象时
        super(null);
        this.helper = helper;
        this.spliterator = spliterator;
        this.targetSize = 0L;
    }

    /**
     * Constructor for non-root nodes.
     *
     * @param parent this node's parent task
     * @param spliterator {@code Spliterator} describing the subtree rooted at
     *        this node, obtained by splitting the parent {@code Spliterator}
     */
    protected AbstractTask(K parent,
                           Spliterator<P_IN> spliterator) {
        super(parent);
        this.spliterator = spliterator;
        this.helper = parent.helper;
        this.targetSize = parent.targetSize;
    }

    /**
     * Constructs a new node of type T whose parent is the receiver; must call
     * the AbstractTask(T, Spliterator) constructor with the receiver and the
     * provided Spliterator.
     *
     * @param spliterator {@code Spliterator} describing the subtree rooted at
     *        this node, obtained by splitting the parent {@code Spliterator}
     * @return newly constructed child node
     * 创建一个子节点
     */
    protected abstract K makeChild(Spliterator<P_IN> spliterator);

    /**
     * Computes the result associated with a leaf node.  Will be called by
     * {@code compute()} and the result passed to @{code setLocalResult()}
     *
     * @return the computed result of a leaf node
     * 计算某个 leaf 的结果
     */
    protected abstract R doLeaf();

    /**
     * Returns a suggested target leaf size based on the initial size estimate.
     *
     * @return suggested target leaf size
     * 获取建议的长度
     */
    public static long suggestTargetSize(long sizeEstimate) {
        long est = sizeEstimate / LEAF_TARGET;
        return est > 0L ? est : 1L;
    }

    /**
     * Returns the targetSize, initializing it via the supplied
     * size estimate if not already initialized.
     */
    protected final long getTargetSize(long sizeEstimate) {
        long s;
        return ((s = targetSize) != 0 ? s :
                (targetSize = suggestTargetSize(sizeEstimate)));
    }

    /**
     * Returns the local result, if any. Subclasses should use
     * {@link #setLocalResult(Object)} and {@link #getLocalResult()} to manage
     * results.  This returns the local result so that calls from within the
     * fork-join framework will return the correct result.
     *
     * @return local result for this node previously stored with
     * {@link #setLocalResult}
     */
    @Override
    public R getRawResult() {
        return localResult;
    }

    /**
     * Does nothing; instead, subclasses should use
     * {@link #setLocalResult(Object)}} to manage results.
     *
     * @param result must be null, or an exception is thrown (this is a safety
     *        tripwire to detect when {@code setRawResult()} is being used
     *        instead of {@code setLocalResult()}
     */
    @Override
    protected void setRawResult(R result) {
        if (result != null)
            throw new IllegalStateException();
    }

    /**
     * Retrieves a result previously stored with {@link #setLocalResult}
     *
     * @return local result for this node previously stored with
     * {@link #setLocalResult}
     */
    protected R getLocalResult() {
        return localResult;
    }

    /**
     * Associates the result with the task, can be retrieved with
     * {@link #getLocalResult}
     *
     * @param localResult local result for this node
     */
    protected void setLocalResult(R localResult) {
        this.localResult = localResult;
    }

    /**
     * Indicates whether this task is a leaf node.  (Only valid after
     * {@link #compute} has been called on this node).  If the node is not a
     * leaf node, then children will be non-null and numChildren will be
     * positive.
     *
     * @return {@code true} if this task is a leaf node
     * 如果没有 child 节点就代表 当前节点是 leaf 节点
     */
    protected boolean isLeaf() {
        return leftChild == null;
    }

    /**
     * Indicates whether this task is the root node
     *
     * @return {@code true} if this task is the root node.
     * 如果没有父节点 就代表 是root 节点
     */
    protected boolean isRoot() {
        return getParent() == null;
    }

    /**
     * Returns the parent of this task, or null if this task is the root
     *
     * @return the parent of this task, or null if this task is the root
     * completer 节点就是父级节点
     */
    @SuppressWarnings("unchecked")
    protected K getParent() {
        return (K) getCompleter();
    }

    /**
     * Decides whether or not to split a task further or compute it
     * directly. If computing directly, calls {@code doLeaf} and pass
     * the result to {@code setRawResult}. Otherwise splits off
     * subtasks, forking one and continuing as the other.
     *
     * <p> The method is structured to conserve resources across a
     * range of uses.  The loop continues with one of the child tasks
     * when split, to avoid deep recursion. To cope with spliterators
     * that may be systematically biased toward left-heavy or
     * right-heavy splits, we alternate which child is forked versus
     * continued in the loop.
     * 计算结果
     */
    @Override
    public void compute() {
        // rs 代表右侧的 迭代器  ls 是 左侧迭代器 一开始 左侧迭代器 为 null
        Spliterator<P_IN> rs = spliterator, ls; // right, left spliterators
        // 预估的长度
        long sizeEstimate = rs.estimateSize();
        long sizeThreshold = getTargetSize(sizeEstimate);
        boolean forkRight = false;
        @SuppressWarnings("unchecked") K task = (K) this;
        // trySplit 会将迭代器 的前半部分拆出来 生成一个新的迭代器
        while (sizeEstimate > sizeThreshold && (ls = rs.trySplit()) != null) {
            // taskToFork 应该是代表 将会被 拆分的节点
            K leftChild, rightChild, taskToFork;
            // 使用左迭代器 生成一个新的 节点对象
            task.leftChild  = leftChild = task.makeChild(ls);
            // 使用右迭代器 生成新节点
            task.rightChild = rightChild = task.makeChild(rs);
            // 为什么设置成1???  每次为 task 对象 设置 子节点时 本 pending 就会变成1
            task.setPendingCount(1);
            // 代表要分解右边  貌似拆分的逻辑是由 fork() 进行的 这里只是设置了 下次要拆分的节点 如果要拆右边 那么 下次while 要处理的就会变成左节点(包括 更换迭代器)
            // 产生的结果并不是一颗平衡树
            if (forkRight) {
                forkRight = false;
                rs = ls;
                task = leftChild;
                taskToFork = rightChild;
            }
            else {
                // 代表本次会分解左边 那么 下次就要分解右边
                forkRight = true;
                task = rightChild;
                taskToFork = leftChild;
            }
            // 拆分任务
            taskToFork.fork();
            sizeEstimate = rs.estimateSize();
        }
        // 将 叶子节点 的结果设置
        task.setLocalResult(task.doLeaf());
        // 将本节点 pending - 1
        task.tryComplete();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote
     * Clears spliterator and children fields.  Overriders MUST call
     * {@code super.onCompletion} as the last thing they do if they want these
     * cleared.
     * 帮助GC 回收
     */
    @Override
    public void onCompletion(CountedCompleter<?> caller) {
        spliterator = null;
        leftChild = rightChild = null;
    }

    /**
     * Returns whether this node is a "leftmost" node -- whether the path from
     * the root to this node involves only traversing leftmost child links.  For
     * a leaf node, this means it is the first leaf node in the encounter order.
     *
     * @return {@code true} if this node is a "leftmost" node
     * 判断本节点是否是 最左节点 也就是不断调用 leftChild 才能获取到
     */
    protected boolean isLeftmostNode() {
        @SuppressWarnings("unchecked")
        K node = (K) this;
        while (node != null) {
            K parent = node.getParent();
            if (parent != null && parent.leftChild != node)
                return false;
            node = parent;
        }
        return true;
    }
}
