/*
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

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.LockSupport;

/**
 * A {@link Future} that may be explicitly completed (setting its
 * value and status), and may be used as a {@link CompletionStage},
 * supporting dependent functions and actions that trigger upon its
 * completion.
 *
 * <p>When two or more threads attempt to
 * {@link #complete complete},
 * {@link #completeExceptionally completeExceptionally}, or
 * {@link #cancel cancel}
 * a CompletableFuture, only one of them succeeds.
 *
 * <p>In addition to these and related methods for directly
 * manipulating status and results, CompletableFuture implements
 * interface {@link CompletionStage} with the following policies: <ul>
 *
 * <li>Actions supplied for dependent completions of
 * <em>non-async</em> methods may be performed by the thread that
 * completes the current CompletableFuture, or by any other caller of
 * a completion method.</li>
 *
 * <li>All <em>async</em> methods without an explicit Executor
 * argument are performed using the {@link ForkJoinPool#commonPool()}
 * (unless it does not support a parallelism level of at least two, in
 * which case, a new Thread is created to run each task).  To simplify
 * monitoring, debugging, and tracking, all generated asynchronous
 * tasks are instances of the marker interface {@link
 * AsynchronousCompletionTask}. </li>
 *
 * <li>All CompletionStage methods are implemented independently of
 * other public methods, so the behavior of one method is not impacted
 * by overrides of others in subclasses.  </li> </ul>
 *
 * <p>CompletableFuture also implements {@link Future} with the following
 * policies: <ul>
 *
 * <li>Since (unlike {@link FutureTask}) this class has no direct
 * control over the computation that causes it to be completed,
 * cancellation is treated as just another form of exceptional
 * completion.  Method {@link #cancel cancel} has the same effect as
 * {@code completeExceptionally(new CancellationException())}. Method
 * {@link #isCompletedExceptionally} can be used to determine if a
 * CompletableFuture completed in any exceptional fashion.</li>
 *
 * <li>In case of exceptional completion with a CompletionException,
 * methods {@link #get()} and {@link #get(long, TimeUnit)} throw an
 * {@link ExecutionException} with the same cause as held in the
 * corresponding CompletionException.  To simplify usage in most
 * contexts, this class also defines methods {@link #join()} and
 * {@link #getNow} that instead throw the CompletionException directly
 * in these cases.</li> </ul>
 *
 * @author Doug Lea
 * @since 1.8
 * 组合对象 可以将多个 future 结果 组合 或消费等
 */
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {

    /*
     * Overview:
     *
     * A CompletableFuture may have dependent completion actions,
     * collected in a linked stack. It atomically completes by CASing
     * a result field, and then pops off and runs those actions. This
     * applies across normal vs exceptional outcomes, sync vs async
     * actions, binary triggers, and various forms of completions.
     *
     * Non-nullness of field result (set via CAS) indicates done.  An
     * AltResult is used to box null as a result, as well as to hold
     * exceptions.  Using a single field makes completion simple to
     * detect and trigger.  Encoding and decoding is straightforward
     * but adds to the sprawl of trapping and associating exceptions
     * with targets.  Minor simplifications rely on (static) NIL (to
     * box null results) being the only AltResult with a null
     * exception field, so we don't usually need explicit comparisons.
     * Even though some of the generics casts are unchecked (see
     * SuppressWarnings annotations), they are placed to be
     * appropriate even if checked.
     *
     * Dependent actions are represented by Completion objects linked
     * as Treiber stacks headed by field "stack". There are Completion
     * classes for each kind of action, grouped into single-input
     * (UniCompletion), two-input (BiCompletion), projected
     * (BiCompletions using either (not both) of two inputs), shared
     * (CoCompletion, used by the second of two sources), zero-input
     * source actions, and Signallers that unblock waiters. Class
     * Completion extends ForkJoinTask to enable async execution
     * (adding no space overhead because we exploit its "tag" methods
     * to maintain claims). It is also declared as Runnable to allow
     * usage with arbitrary executors.
     *
     * Support for each kind of CompletionStage relies on a separate
     * class, along with two CompletableFuture methods:
     *
     * * A Completion class with name X corresponding to function,
     *   prefaced with "Uni", "Bi", or "Or". Each class contains
     *   fields for source(s), actions, and dependent. They are
     *   boringly similar, differing from others only with respect to
     *   underlying functional forms. We do this so that users don't
     *   encounter layers of adaptors in common usages. We also
     *   include "Relay" classes/methods that don't correspond to user
     *   methods; they copy results from one stage to another.
     *
     * * Boolean CompletableFuture method x(...) (for example
     *   uniApply) takes all of the arguments needed to check that an
     *   action is triggerable, and then either runs the action or
     *   arranges its async execution by executing its Completion
     *   argument, if present. The method returns true if known to be
     *   complete.
     *
     * * Completion method tryFire(int mode) invokes the associated x
     *   method with its held arguments, and on success cleans up.
     *   The mode argument allows tryFire to be called twice (SYNC,
     *   then ASYNC); the first to screen and trap exceptions while
     *   arranging to execute, and the second when called from a
     *   task. (A few classes are not used async so take slightly
     *   different forms.)  The claim() callback suppresses function
     *   invocation if already claimed by another thread.
     *
     * * CompletableFuture method xStage(...) is called from a public
     *   stage method of CompletableFuture x. It screens user
     *   arguments and invokes and/or creates the stage object.  If
     *   not async and x is already complete, the action is run
     *   immediately.  Otherwise a Completion c is created, pushed to
     *   x's stack (unless done), and started or triggered via
     *   c.tryFire.  This also covers races possible if x completes
     *   while pushing.  Classes with two inputs (for example BiApply)
     *   deal with races across both while pushing actions.  The
     *   second completion is a CoCompletion pointing to the first,
     *   shared so that at most one performs the action.  The
     *   multiple-arity methods allOf and anyOf do this pairwise to
     *   form trees of completions.
     *
     * Note that the generic type parameters of methods vary according
     * to whether "this" is a source, dependent, or completion.
     *
     * Method postComplete is called upon completion unless the target
     * is guaranteed not to be observable (i.e., not yet returned or
     * linked). Multiple threads can call postComplete, which
     * atomically pops each dependent action, and tries to trigger it
     * via method tryFire, in NESTED mode.  Triggering can propagate
     * recursively, so NESTED mode returns its completed dependent (if
     * one exists) for further processing by its caller (see method
     * postFire).
     *
     * Blocking methods get() and join() rely on Signaller Completions
     * that wake up waiting threads.  The mechanics are similar to
     * Treiber stack wait-nodes used in FutureTask, Phaser, and
     * SynchronousQueue. See their internal documentation for
     * algorithmic details.
     *
     * Without precautions, CompletableFutures would be prone to
     * garbage accumulation as chains of Completions build up, each
     * pointing back to its sources. So we null out fields as soon as
     * possible (see especially method Completion.detach). The
     * screening checks needed anyway harmlessly ignore null arguments
     * that may have been obtained during races with threads nulling
     * out fields.  We also try to unlink fired Completions from
     * stacks that might never be popped (see method postFire).
     * Completion fields need not be declared as final or volatile
     * because they are only visible to other threads upon safe
     * publication.
     */

    /**
     * 当前对象的结果 或者一个包装的异常对象(AltResult)  result 不会设置为null 而是用一个 NIL 对象 也就是 AltResult(null) 来替换
     */
    volatile Object result;       // Either the result or boxed AltResult
    /**
     * 任务栈 内部是一个链表结构  内部通过next 字段关联
     */
    volatile Completion stack;    // Top of Treiber stack of dependent actions

    /**
     * 设置结果到 result字段
     * @param r
     * @return
     */
    final boolean internalComplete(Object r) { // CAS from null to r
        return UNSAFE.compareAndSwapObject(this, RESULT, null, r);
    }

    /**
     * 使用CAS 尝试将 stack 从 cmp 替换成 val
     * @param cmp
     * @param val
     * @return
     */
    final boolean casStack(Completion cmp, Completion val) {
        return UNSAFE.compareAndSwapObject(this, STACK, cmp, val);
    }

    /** Returns true if successfully pushed c onto stack. */
    /**
     * 尝试将 某个Completion 推入到栈结构中
     * @param c
     * @return
     */
    final boolean tryPushStack(Completion c) {
        // 获取当前栈顶对象
        Completion h = stack;
        // 将 h 设置到 c 的 next 上
        lazySetNext(c, h);
        // CAS 替换 stack 信息  这样通过stack 获取到 栈顶元素后 可以通过next 方法获取到之前的栈顶元素
        return UNSAFE.compareAndSwapObject(this, STACK, h, c);
    }

    /** Unconditionally pushes c onto stack, retrying if necessary. */
    /**
     * 自旋直到某个任务被成功添加到 stack上
     * @param c
     */
    final void pushStack(Completion c) {
        do {} while (!tryPushStack(c));
    }

    /* ------------- Encoding and decoding outcomes -------------- */

    /**
     * 异常的包装对象
     */
    static final class AltResult { // See above
        final Throwable ex;        // null only for NIL
        AltResult(Throwable x) { this.ex = x; }
    }

    /** The encoding of the null value. */
    /**
     * 包装了 null 的结果对象
     */
    static final AltResult NIL = new AltResult(null);

    /** Completes with the null value, unless already completed. */
    /**
     * 将result 的 null 替换成 NIL
     * @return
     */
    final boolean completeNull() {
        return UNSAFE.compareAndSwapObject(this, RESULT, null,
                                           NIL);
    }

    /** Returns the encoding of the given non-exceptional value. */
    /**
     * 如果 t 是 null 将其包装成 NIL
     * @param t
     * @return
     */
    final Object encodeValue(T t) {
        return (t == null) ? NIL : t;
    }

    /** Completes with a non-exceptional result, unless already completed. */
    /**
     * 将结果填充到 result 字段中 如果是 null 就要替换成NIL
     * @param t
     * @return
     */
    final boolean completeValue(T t) {
        return UNSAFE.compareAndSwapObject(this, RESULT, null,
                                           (t == null) ? NIL : t);
    }

    /**
     * Returns the encoding of the given (non-null) exception as a
     * wrapped CompletionException unless it is one already.
     * 将异常包装成 CompletionException 后包装成 AltResult 并返回
     */
    static AltResult encodeThrowable(Throwable x) {
        return new AltResult((x instanceof CompletionException) ? x :
                             new CompletionException(x));
    }

    /** Completes with an exceptional result, unless already completed. */
    /**
     * 将异常包装后设置到 Result 中
     * @param x
     * @return
     */
    final boolean completeThrowable(Throwable x) {
        return UNSAFE.compareAndSwapObject(this, RESULT, null,
                                           encodeThrowable(x));
    }

    /**
     * Returns the encoding of the given (non-null) exception as a
     * wrapped CompletionException unless it is one already.  May
     * return the given Object r (which must have been the result of a
     * source future) if it is equivalent, i.e. if this is a simple
     * relay of an existing CompletionException.
     */
    static Object encodeThrowable(Throwable x, Object r) {
        // 如果异常不是 CompletionException 就包装成对应的类型
        if (!(x instanceof CompletionException))
            x = new CompletionException(x);
        // 如果 x 已经是该类型了 且 r 中的异常信息 等同于 x  直接返回r
        else if (r instanceof AltResult && x == ((AltResult)r).ex)
            return r;
        // 否则将 包装后的 CompletionException 包装成 AltResult 对象 并返回
        return new AltResult(x);
    }

    /**
     * Completes with the given (non-null) exceptional result as a
     * wrapped CompletionException unless it is one already, unless
     * already completed.  May complete with the given Object r
     * (which must have been the result of a source future) if it is
     * equivalent, i.e. if this is a simple propagation of an
     * existing CompletionException.
     * 根据情况 将result 设置为 包装对象
     */
    final boolean completeThrowable(Throwable x, Object r) {
        return UNSAFE.compareAndSwapObject(this, RESULT, null,
                                           encodeThrowable(x, r));
    }

    /**
     * Returns the encoding of the given arguments: if the exception
     * is non-null, encodes as AltResult.  Otherwise uses the given
     * value, boxed as NIL if null.
     */
    Object encodeOutcome(T t, Throwable x) {
        return (x == null) ? (t == null) ? NIL : t : encodeThrowable(x);
    }

    /**
     * Returns the encoding of a copied outcome; if exceptional,
     * rewraps as a CompletionException, else returns argument.
     */
    static Object encodeRelay(Object r) {
        Throwable x;
        return (((r instanceof AltResult) &&
                 (x = ((AltResult)r).ex) != null &&
                 !(x instanceof CompletionException)) ?
                // 如果是异常对象 生成一个副本对象 否则 直接返回
                new AltResult(new CompletionException(x)) : r);
    }

    /**
     * Completes with r or a copy of r, unless already completed.
     * If exceptional, r is first coerced to a CompletionException.
     * 包装结果后设置到 result 中
     */
    final boolean completeRelay(Object r) {
        return UNSAFE.compareAndSwapObject(this, RESULT, null,
                                           encodeRelay(r));
    }

    /**
     * Reports result using Future.get conventions.
     * 当调用 get() 方法时 应该是类似过滤结果
     */
    private static <T> T reportGet(Object r)
        throws InterruptedException, ExecutionException {
        // 如果拉取到的结果为null 代表本次尝试获取 被打断了
        if (r == null) // by convention below, null means interrupted
            throw new InterruptedException();
        // 如果 r 已经被包装
        if (r instanceof AltResult) {
            Throwable x, cause;
            // 没有异常信息 也就是 NLT 直接返回null
            if ((x = ((AltResult)r).ex) == null)
                return null;
            // 抛出对应的类型
            if (x instanceof CancellationException)
                throw (CancellationException)x;
            // CompletionException 内部包装了真正的异常  这里将异常 包装为 ExecutionException 并抛出
            if ((x instanceof CompletionException) &&
                (cause = x.getCause()) != null)
                x = cause;
            throw new ExecutionException(x);
        }
        // 正常返回
        @SuppressWarnings("unchecked") T t = (T) r;
        return t;
    }

    /**
     * Decodes outcome to return result or throw unchecked exception.
     * 当调用 join 方法后触发 同样是评估返回结果
     */
    private static <T> T reportJoin(Object r) {
        if (r instanceof AltResult) {
            Throwable x;
            if ((x = ((AltResult)r).ex) == null)
                return null;
            if (x instanceof CancellationException)
                throw (CancellationException)x;
            if (x instanceof CompletionException)
                throw (CompletionException)x;
            throw new CompletionException(x);
        }
        @SuppressWarnings("unchecked") T t = (T) r;
        return t;
    }

    /* ------------- Async task preliminaries -------------- */

    /**
     * A marker interface identifying asynchronous tasks produced by
     * {@code async} methods. This may be useful for monitoring,
     * debugging, and tracking asynchronous activities.
     *
     * @since 1.8
     * 代表异步任务接口
     */
    public static interface AsynchronousCompletionTask {
    }

    /**
     * 这里 根据 FJPool的 公共并行度是否 超过1 确定是否使用 公共线程池 也就是如果并行度过低 可能要使用其他线程池
     */
    private static final boolean useCommonPool =
        (ForkJoinPool.getCommonPoolParallelism() > 1);

    /**
     * Default executor -- ForkJoinPool.commonPool() unless it cannot
     * support parallelism.
     * 判断是否允许并行 允许的话直接使用 FJPool 的common线程池 否则使用一个简易的线程池对象 不具备维护池化功能 而是直接创建新线程执行任务
     * 一般情况下都会使用 fjPool 处理任务
     */
    private static final Executor asyncPool = useCommonPool ?
        ForkJoinPool.commonPool() : new ThreadPerTaskExecutor();

    /** Fallback if ForkJoinPool.commonPool() cannot support parallelism */
    static final class ThreadPerTaskExecutor implements Executor {
        public void execute(Runnable r) { new Thread(r).start(); }
    }

    /**
     * Null-checks user executor argument, and translates uses of
     * commonPool to asyncPool in case parallelism disabled.
     * 针对线程池相关信息 进行参数校验
     */
    static Executor screenExecutor(Executor e) {
        // 如果 不允许使用common 就替换成ThreadPerTaskExecutor
        if (!useCommonPool && e == ForkJoinPool.commonPool())
            return asyncPool;
        if (e == null) throw new NullPointerException();
        // 非空情况下 直接返回 common
        return e;
    }

    // Modes for Completion.tryFire. Signedness matters.
    /**
     * 代表任务的触发模式
     */
    static final int SYNC   =  0; // 同步模式
    static final int ASYNC  =  1; // 异步模式
    static final int NESTED = -1; // 嵌套模式

    /* ------------- Base Completion classes and operations -------------- */

    /**
     * 该对象作为 FJTask 的子类 同时实现异步任务接口
     */
    @SuppressWarnings("serial")
    abstract static class Completion extends ForkJoinTask<Void>
        implements Runnable, AsynchronousCompletionTask {
        /**
         * 内部使用链式结构   当替换 CompletableFuture 的 stack 时 会将之前的 结果 设置到字段的 next 中
         */
        volatile Completion next;      // Treiber stack link

        /**
         * Performs completion action if triggered, returning a
         * dependent that may need propagation, if one exists.
         *
         * @param mode SYNC, ASYNC, or NESTED
         *             传入指定的 触发类型 并触发任务
         */
        abstract CompletableFuture<?> tryFire(int mode);

        /** Returns true if possibly still triggerable. Used by cleanStack. */
        /**
         * 判断当前任务是否还能被触发
         * @return
         */
        abstract boolean isLive();

        /**
         * 使用异步模式 触发任务
         */
        public final void run()                { tryFire(ASYNC); }

        /**
         * 使用异步模式触发任务 且返回true 代表本次任务已经完成  针对CC 任务 这里会返回false
         * @return
         */
        public final boolean exec()            { tryFire(ASYNC); return true; }

        // 获取结果的 函数始终是空实现
        public final Void getRawResult()       { return null; }
        public final void setRawResult(Void v) {}
    }

    /**
     * 就是将 给定的 c 的next 属性替换成需要的对象
     * @param c
     * @param next
     */
    static void lazySetNext(Completion c, Completion next) {
        UNSAFE.putOrderedObject(c, NEXT, next);
    }

    /**
     * Pops and tries to trigger all reachable dependents.  Call only
     * when known to be done.
     * 尝试触发所有可达的依赖
     * 该方法会从当前栈顶 开始 执行 tryfire 方法并将所有经过的CompletableFuture 对象的 任务全部转移到当前对象的栈顶
     * 全部转移完后 又会回到this 并按照从栈顶往下的顺序 执行 tryFire 相当于一个 拓扑算法
     * 该调用链就是从 root 源开始 不断往下 执行所有的钩子 (就是将数据转换到 dep 上)
     */
    final void postComplete() {
        /*
         * On each step, variable f holds current dependents to pop
         * and run.  It is extended along only one path at a time,
         * pushing others to avoid unbounded recursion.
         */
        CompletableFuture<?> f = this; Completion h;
        // 确保 当前存在栈顶任务 （该f 可能是原对象 也可能是 执行fire 后返回的结果对象）
        while ((h = f.stack) != null ||
                // 这里 f 已经不是 原对象了 如果 这时将 f 重新指向栈顶 且还有元素 (一般来说任务应该已经被清除掉了)
               (f != this && (h = (f = this).stack) != null)) {
            CompletableFuture<?> d; Completion t;
            // 获取栈顶的下个任务 用该任务来替换栈顶  一般情况下每个function 都会创建一个对应的动作 设置到 source 上 这时就在栈的顶端
            // 当调用get() 时 生成的节点会在 正常函数的 顶部 这里取出 正常函数 并执行
            if (f.casStack(h, t = h.next)) {
                // 如果存在 原栈顶的下个元素
                if (t != null) {
                    // 如果 f 已经指向其他节点 将其他节点 栈顶的任务转移到 当前任务中
                    if (f != this) {
                        pushStack(h);
                        continue;
                    }
                    // 将原栈顶 与next 节点关联置空
                    h.next = null;    // detach
                }
                // 首先先看下面  代表 栈顶下没有其他任务  这时触发栈顶的任务 注意是嵌套模式
                // 如果当前是 a 为 d1 赋值后会将d1 弹出 这样就可以继续执行d1 的action 并为d2 赋值
                // 代表着 如果是嵌套模式 将 有最初的源头来 执行action
                // 返回null 时 代表 该d 节点 没有下游对象了 这样就不满足上面 f != this 的条件 就退出循环
                // 还有种可能就是下游是 异步执行的 那么 这里也会返回null 而异步节点执行完之后 会自己执行一个 postComplete 保证能继续传播
                // 如果是 get 的话 使用信号阻塞后 tryFire 也会返回null 但是一般 栈还会有action 这样就可以继续执行
                // 也就是get 只是阻塞到 a 设置结果 而没有阻塞到其他action 的结果都设置完毕
                f = (d = h.tryFire(NESTED)) == null ? this : d;
            }
        }
    }

    /** Traverses stack and unlinks dead Completions. */
    /**
     * 遍历栈 并将 已经死亡的任务清除
     */
    final void cleanStack() {
        for (Completion p = null, q = stack; q != null;) {
            // 从栈顶开始往下遍历
            Completion s = q.next;
            // 判断当前任务是否还能触发  由子类实现
            if (q.isLive()) {
                // p保留本次的结果
                p = q;
                // q代表next节点
                q = s;
            }
            // 这里代表 p 被置空了 需要被丢弃
            else if (p == null) {
                // 相当于 将q 从链表中移除 将s 也就是 q.next 作为新的栈顶
                casStack(q, s);
                q = stack;
            }
            // 进入这里 p 代表 q 的上个节点  这里将上个节点指向他就代表 q 节点本身无效了 对应上面 q.isLive() = false
            else {
                p.next = s;
                // 判断q 的上个节点是否有效  一般来说应该是有效的 不排除之后突然无效的情况 有效的情况下 将q 指向s
                if (p.isLive())
                    q = s;
                else {
                    // 如果 之后 p 无效了 就代表需要被剔除 将q 重新指向栈顶
                    p = null;  // restart
                    q = stack;
                }
            }
        }
    }

    /* ------------- One-input Completions -------------- */

    /** A Completion with a source, dependent, and executor. */
    @SuppressWarnings("serial")
    abstract static class UniCompletion<T,V> extends Completion {
        /**
         * 代表执行该任务的线程池  如果有线程池 应该是意味着 这是一个 异步任务
         */
        Executor executor;                 // executor to use (null if none)
        /**
         * 该任务需要 依赖于其他任务
         */
        CompletableFuture<V> dep;          // the dependent to complete
        /**
         * 该任务是从那个 future 对象发出的
         */
        CompletableFuture<T> src;          // source for action

        /**
         * 通过传入的线程池对象进行初始化
         * @param executor
         * @param dep
         * @param src
         */
        UniCompletion(Executor executor, CompletableFuture<V> dep,
                      CompletableFuture<T> src) {
            this.executor = executor; this.dep = dep; this.src = src;
        }

        /**
         * Returns true if action can be run. Call only when known to
         * be triggerable. Uses FJ tag bit to ensure that only one
         * thread claims ownership.  If async, starts as task -- a
         * later call to tryFire will run action.
         * 同步 或者 嵌套模式下会触发该方法 判断该 action 是否可以运行
         */
        final boolean claim() {
            Executor e = executor;
            // 将该任务的 低16位设置成1  这个应该就是单纯的避免执行多次 代表该task 已经有某个 visitor 了
            if (compareAndSetForkJoinTaskTag((short)0, (short)1)) {
                // 因为内部没有线程池 无法异步执行 返回true 在外部就会同步执行
                if (e == null)
                    return true;
                // 使用线程池执行 返回false 这样会阻止外部以同步 或者嵌套模式执行
                executor = null; // disable
                e.execute(this);
            }
            return false;
        }

        /**
         * 依赖的任务还存在 代表还处在存活状态
         * @return
         */
        final boolean isLive() { return dep != null; }
    }

    /** Pushes the given completion (if it exists) unless done. */
    /**
     * 将 UniCompletion 设置到栈中
     * @param c
     */
    final void push(UniCompletion<?,?> c) {
        if (c != null) {
            while (result == null && !tryPushStack(c))
                lazySetNext(c, null); // clear on failure
        }
    }

    /**
     * Post-processing by dependent after successful UniCompletion
     * tryFire.  Tries to clean stack of source a, and then either runs
     * postComplete or returns this to caller, depending on mode.
     */
    final CompletableFuture<T> postFire(CompletableFuture<?> a, int mode) {
        if (a != null && a.stack != null) {
            if (mode < 0 || a.result == null)
                a.cleanStack();
            else
                // 异步 或者同步 都能保证继续传播下去 比如使用最初的源点使用嵌套模式往下执行 发现是个异步节点 那里会直接断掉 而这里就会继续执行
                // 这里没有直接掉 clean 是为了能进入 stack!=null 的判断 进而进入下一个tryFire方法  不过异步情况下还是没什么
                // 作用 因为 d 已经被清除了 会直接返回null
                // 同步模式情况就是 直接传递数据到下游 这里逻辑跟异步是一样的
                a.postComplete();
        }
        // 该对象是d
        if (result != null && stack != null) {
            if (mode < 0)
                // 如果是嵌套模式返回本对象 而不是往下 传递结果 这样该对象 会转移到栈中继续执行
                // 那么就能实现 从 a(source) -> 通过调用action -> 为d1赋值 -> 将该action 从栈中移除 -> 返回d1并移动到a 的栈中
                // 这样 直接从a 的栈就可以继续 调用 d1 的action 为 d2 赋值了不断重复下去
                return this;
            else
                // 异步或者同步模式会继续传递 就能保证数据从上游一直传到下游
                postComplete();
        }
        // 返回null 代表该 d 对象 没有下游节点了 也就是没有为下面的 dx 设置 值的action
        return null;
    }

    @SuppressWarnings("serial")
    static final class UniApply<T,V> extends UniCompletion<T,V> {
        Function<? super T,? extends V> fn;
        UniApply(Executor executor, CompletableFuture<V> dep,
                 CompletableFuture<T> src,
                 Function<? super T,? extends V> fn) {
            super(executor, dep, src); this.fn = fn;
        }
        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d; CompletableFuture<T> a;
            if ((d = dep) == null ||
                !d.uniApply(a = src, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final <S> boolean uniApply(CompletableFuture<S> a,
                               Function<? super S,? extends T> f,
                               UniApply<S,T> c) {
        Object r; Throwable x;
        if (a == null || (r = a.result) == null || f == null)
            return false;
        tryComplete: if (result == null) {
            if (r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            try {
                if (c != null && !c.claim())
                    return false;
                @SuppressWarnings("unchecked") S s = (S) r;
                completeValue(f.apply(s));
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <V> CompletableFuture<V> uniApplyStage(
        Executor e, Function<? super T,? extends V> f) {
        if (f == null) throw new NullPointerException();
        CompletableFuture<V> d =  new CompletableFuture<V>();
        if (e != null || !d.uniApply(this, f, null)) {
            UniApply<T,V> c = new UniApply<T,V>(e, d, this, f);
            push(c);
            c.tryFire(SYNC);
        }
        return d;
    }

    @SuppressWarnings("serial")
    static final class UniAccept<T> extends UniCompletion<T,Void> {
        Consumer<? super T> fn;
        UniAccept(Executor executor, CompletableFuture<Void> dep,
                  CompletableFuture<T> src, Consumer<? super T> fn) {
            super(executor, dep, src); this.fn = fn;
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d; CompletableFuture<T> a;
            if ((d = dep) == null ||
                !d.uniAccept(a = src, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final <S> boolean uniAccept(CompletableFuture<S> a,
                                Consumer<? super S> f, UniAccept<S> c) {
        Object r; Throwable x;
        if (a == null || (r = a.result) == null || f == null)
            return false;
        tryComplete: if (result == null) {
            if (r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            try {
                if (c != null && !c.claim())
                    return false;
                @SuppressWarnings("unchecked") S s = (S) r;
                f.accept(s);
                completeNull();
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private CompletableFuture<Void> uniAcceptStage(Executor e,
                                                   Consumer<? super T> f) {
        if (f == null) throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (e != null || !d.uniAccept(this, f, null)) {
            UniAccept<T> c = new UniAccept<T>(e, d, this, f);
            push(c);
            c.tryFire(SYNC);
        }
        return d;
    }

    @SuppressWarnings("serial")
    static final class UniRun<T> extends UniCompletion<T,Void> {
        Runnable fn;
        UniRun(Executor executor, CompletableFuture<Void> dep,
               CompletableFuture<T> src, Runnable fn) {
            super(executor, dep, src); this.fn = fn;
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d; CompletableFuture<T> a;
            if ((d = dep) == null ||
                !d.uniRun(a = src, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final boolean uniRun(CompletableFuture<?> a, Runnable f, UniRun<?> c) {
        Object r; Throwable x;
        if (a == null || (r = a.result) == null || f == null)
            return false;
        if (result == null) {
            if (r instanceof AltResult && (x = ((AltResult)r).ex) != null)
                completeThrowable(x, r);
            else
                try {
                    if (c != null && !c.claim())
                        return false;
                    f.run();
                    completeNull();
                } catch (Throwable ex) {
                    completeThrowable(ex);
                }
        }
        return true;
    }

    private CompletableFuture<Void> uniRunStage(Executor e, Runnable f) {
        if (f == null) throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (e != null || !d.uniRun(this, f, null)) {
            UniRun<T> c = new UniRun<T>(e, d, this, f);
            push(c);
            c.tryFire(SYNC);
        }
        return d;
    }

    /**
     * 代表单数据源消费
     * @param <T>
     */
    @SuppressWarnings("serial")
    static final class UniWhenComplete<T> extends UniCompletion<T,T> {
        /**
         * 消费函数
         */
        BiConsumer<? super T, ? super Throwable> fn;
        UniWhenComplete(Executor executor, CompletableFuture<T> dep,
                        CompletableFuture<T> src,
                        BiConsumer<? super T, ? super Throwable> fn) {
            super(executor, dep, src); this.fn = fn;
        }

        /**
         * 代表 该动作被触发时执行的动作
         * @param mode SYNC, ASYNC, or NESTED
         * @return
         */
        final CompletableFuture<T> tryFire(int mode) {
            CompletableFuture<T> d; CompletableFuture<T> a;
            // dep == null 代表已经执行过一次了 不能重复执行
            if ((d = dep) == null ||
                    // 异步模式 不传入 本对象(这样能确保 方法正常调用 其余模式要传入是为了确保该对象没有设置线程池)  如果源头结果还是没有 那么还是返回null
                !d.uniWhenComplete(a = src, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    /**
     * 以 a 作为数据源头 一旦该数据有result 之后 通过指定函数去消耗它
     * @param a
     * @param f
     * @param c  可能为null 而 c 为null 的情况 就是不打算以异步方式执行 异步方式 一定会包装一个 UniWhenComplete 对象
     * @return
     */
    final boolean uniWhenComplete(CompletableFuture<T> a,
                                  BiConsumer<? super T,? super Throwable> f,
                                  UniWhenComplete<T> c) {
        Object r; T t; Throwable x = null;
        // 如果 源头结果还没有生成 直接返回 之后会将action 封装成对象后设置到 a 上这样在 a 的结果获取到后就触发之前未执行的逻辑
        if (a == null || (r = a.result) == null || f == null)
            return false;
        // 如果当前结果还没有生成  这时就要设置result
        if (result == null) {
            try {
                // 当设置动作时的首次调用 c 会为null  在首次调用失败后 才会封装成c 对象 并 在源头获取到结果后触发
                // 异步模式下 c 也是null 因为能以异步方式执行之后 c.claim() 内部的线程池会被置空 就一定返回false 而异步模式 又是允许执行的 所以直接传入 null 确保 不做这里的判断
                // 因为如果是异步模式 会使用c 内部维护的线程池重新跑一次 tryFire(ASYNC) 方法 同步或嵌套模式被阻止是因为 c 的执行需要交托给内部的线程池
                // 该方法只能以异步方式执行一次 之后 线程池 置空后 就会返回true 允许后面的执行
                // 异步只执行一次的前提是 已经生成结果了 如果没有生成结果 是不会使用线程池执行的
                if (c != null && !c.claim())
                    return false;
                // 如果结果为null 或者出现了异常 t 设置为null
                if (r instanceof AltResult) {
                    x = ((AltResult)r).ex;
                    t = null;
                } else {
                    @SuppressWarnings("unchecked") T tr = (T) r;
                    t = tr;
                }
                // 将 结果和异常信息传入函数
                f.accept(t, x);
                // 确保没有异常的情况下
                if (x == null) {
                    // 这里将 a 的结果 设置到了 d 里面
                    internalComplete(r);
                    return true;
                }
            } catch (Throwable ex) {
                if (x == null)
                    x = ex;
            }
            // 设置异常结果
            completeThrowable(x, r);
        }
        return true;
    }

    /**
     * 针对完成时 的联接函数
     * @param e 在同步模式下 为null 代表不使用额外的线程池去执行
     * @param f 消费者函数 处理 生成结果后的 future 对象  BiConsumer 代表消费2个参数 第一个代表是正常生成的 result 对象 第二个是 生成的异常对象
     * @return
     */
    private CompletableFuture<T> uniWhenCompleteStage(
        Executor e, BiConsumer<? super T, ? super Throwable> f) {
        // 非空校验
        if (f == null) throw new NullPointerException();

        // 创建一个下游对象
        CompletableFuture<T> d = new CompletableFuture<T>();
        // 新建的 对象会将 自身作为上游对象 只有上游对象的result 已经设置的情况下才会唤醒下游
        // 在设置该方法的 时候 立即检测一次 当前future 的 result 是否已经设置

        // 如果使用异步执行 或者当前结果还没有生成 那么 该消费函数 肯定是在后面的某个时刻才能触发 为了保留这个动作 将动作封装成一个节点后设置到上游对象
        // 比如 future.whenComplete 这时 就会将 动作封装后设置到 future 上
        // 这样做的好处是不会阻塞主线程
        if (e != null || !d.uniWhenComplete(this, f, null)) {
            // 将action 封装起来  这里传入了 线程池 也就是 即使 是同步模式或者嵌套模式 调用 tryFire 也是无法获取结果的 因为 内部会判断 c.claim 是否为true 而有设置线程池的情况 会返回false
            // 为什么要这样做??? 现在的认知就是 先创建a 之后 设置各种后置函数 ， 这时 a 的结果可能已经生成了 这样结果就会直接进入到 d
            // 而如果a的生成 是比较耗时的操作 就会将 后面的任务 添加到a 后面 这样a 在完成时 就会自发的将结果设置到 d 中
            // 而d的后一个action 会添加到 d 中 当d 设置完结果后 又会触发 postComplete 这样继续传递结果
            // 这里能否顺利的将结果 从a 传递到d 还有一个制约因素 就是 UniWhenComplete 对象 是否设置 线程池属性
            // 当a result 生成的时候 会以嵌套模式传递结果 这时如果 UniWhenComplete.excute != null 那么不会将结果传递到d 为什么这样设计??? 可能为传递本身增加了一个 触发机制 比如通过某种方式
            // 一次性将a的结果不断传递下去 就类似响应式的 cold 和 hot 概念一样
            UniWhenComplete<T> c = new UniWhenComplete<T>(e, d, this, f);
            // 将 c 添加到 当前对象中
            // 梳理一下 a 对象在调用 whenComplete 后返回一个新的 d 对象 而 a 对象后面追加了一个基于a处理并将结果设置到 d 的节点
            push(c);

            // 这里如果内部设置了线程池 就是使用 异步执行 而且只能异步执行一次 之后会将线程池置空 且之后 无论哪种模式都是同步调用 (因为线程池已经置空了c.claim 始终是false 无论怎么调用都是在当前线程执行)
            // 这里使用同步模式 如果条件允许的话 现在就会直接传递数据到下游 否则通过嵌套模式传递
            // 如果 同步模式 不具备传递功能的话 而这里又获取了结果 那么嵌套模式执行的时候 会发现 d 已经被清除就没办法传递了
            // 所以 同步模式必须具备 自主的 传递功能
            c.tryFire(SYNC);
        }

        // 如果是同步模式 会先尝试直接获取一次结果 如果没有获取到的情况  将这个action的执行时机延后 从而不阻塞主线程， 做法就是生成了一个 将a 的 result 传播到d 的 result 的节点对象
        // 该节点是设置在 a 上的 然后返回了 d 节点 注意这里  a和d 是没有直接关联的  不过a 的Complete 链上有一个动作 就是当a 的result 已经生成的时候将结果转移到 d 上 这时 d 也就有了结果
        // 生成a 节点的 任务 在使用 线程池 异步执行后 马上就会触发 下游节点 这样就会执行到设置d result 的 action
        return d;
    }

    @SuppressWarnings("serial")
    static final class UniHandle<T,V> extends UniCompletion<T,V> {
        BiFunction<? super T, Throwable, ? extends V> fn;
        UniHandle(Executor executor, CompletableFuture<V> dep,
                  CompletableFuture<T> src,
                  BiFunction<? super T, Throwable, ? extends V> fn) {
            super(executor, dep, src); this.fn = fn;
        }
        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d; CompletableFuture<T> a;
            if ((d = dep) == null ||
                !d.uniHandle(a = src, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final <S> boolean uniHandle(CompletableFuture<S> a,
                                BiFunction<? super S, Throwable, ? extends T> f,
                                UniHandle<S,T> c) {
        Object r; S s; Throwable x;
        if (a == null || (r = a.result) == null || f == null)
            return false;
        if (result == null) {
            try {
                if (c != null && !c.claim())
                    return false;
                if (r instanceof AltResult) {
                    x = ((AltResult)r).ex;
                    s = null;
                } else {
                    x = null;
                    @SuppressWarnings("unchecked") S ss = (S) r;
                    s = ss;
                }
                completeValue(f.apply(s, x));
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <V> CompletableFuture<V> uniHandleStage(
        Executor e, BiFunction<? super T, Throwable, ? extends V> f) {
        if (f == null) throw new NullPointerException();
        CompletableFuture<V> d = new CompletableFuture<V>();
        if (e != null || !d.uniHandle(this, f, null)) {
            UniHandle<T,V> c = new UniHandle<T,V>(e, d, this, f);
            push(c);
            c.tryFire(SYNC);
        }
        return d;
    }

    /**
     * 该对象看来是没有异步执行机制的 应该是因为他是一个终结点的关系吧
     * @param <T>
     */
    @SuppressWarnings("serial")
    static final class UniExceptionally<T> extends UniCompletion<T,T> {
        Function<? super Throwable, ? extends T> fn;
        UniExceptionally(CompletableFuture<T> dep, CompletableFuture<T> src,
                         Function<? super Throwable, ? extends T> fn) {
            super(null, dep, src); this.fn = fn;
        }
        final CompletableFuture<T> tryFire(int mode) { // never ASYNC
            // assert mode != ASYNC;
            CompletableFuture<T> d; CompletableFuture<T> a;
            if ((d = dep) == null || !d.uniExceptionally(a = src, fn, this))
                return null;
            dep = null; src = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final boolean uniExceptionally(CompletableFuture<T> a,
                                   Function<? super Throwable, ? extends T> f,
                                   UniExceptionally<T> c) {
        Object r; Throwable x;
        if (a == null || (r = a.result) == null || f == null)
            return false;
        if (result == null) {
            try {
                if (r instanceof AltResult && (x = ((AltResult)r).ex) != null) {
                    // 同样的套路 第一次 c 为null 总能直接调用 之后如果是同步模式这里返回false 内部转发给线程池 再之后一直都是同步调用
                    if (c != null && !c.claim())
                        return false;
                    // 将异常转换为正常对象后设置到 对应节点中 注意该节点不是 首节点而是 某个中间节点
                    completeValue(f.apply(x));
                } else
                    internalComplete(r);
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    /**
     * 代表当本节点 完成后 如果产生了异常使用fun 去处理 当前节点 是整个调用链中间的某个点 而不是起始节点
     * @param f
     * @return
     */
    private CompletableFuture<T> uniExceptionallyStage(
        Function<Throwable, ? extends T> f) {
        if (f == null) throw new NullPointerException();
        CompletableFuture<T> d = new CompletableFuture<T>();
        // 直接判断是否有异常了
        if (!d.uniExceptionally(this, f, null)) {
            // 封装节点设置到 栈结构中
            UniExceptionally<T> c = new UniExceptionally<T>(d, this, f);
            push(c);
            // 该方法 不允许异步调用 再次尝试获取结果
            c.tryFire(SYNC);
        }
        return d;
    }

    @SuppressWarnings("serial")
    static final class UniRelay<T> extends UniCompletion<T,T> { // for Compose
        UniRelay(CompletableFuture<T> dep, CompletableFuture<T> src) {
            super(null, dep, src);
        }
        final CompletableFuture<T> tryFire(int mode) {
            CompletableFuture<T> d; CompletableFuture<T> a;
            if ((d = dep) == null || !d.uniRelay(a = src))
                return null;
            src = null; dep = null;
            return d.postFire(a, mode);
        }
    }

    final boolean uniRelay(CompletableFuture<T> a) {
        Object r;
        if (a == null || (r = a.result) == null)
            return false;
        if (result == null) // no need to claim
            completeRelay(r);
        return true;
    }

    @SuppressWarnings("serial")
    static final class UniCompose<T,V> extends UniCompletion<T,V> {
        Function<? super T, ? extends CompletionStage<V>> fn;
        UniCompose(Executor executor, CompletableFuture<V> dep,
                   CompletableFuture<T> src,
                   Function<? super T, ? extends CompletionStage<V>> fn) {
            super(executor, dep, src); this.fn = fn;
        }
        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d; CompletableFuture<T> a;
            if ((d = dep) == null ||
                !d.uniCompose(a = src, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; fn = null;
            return d.postFire(a, mode);
        }
    }

    final <S> boolean uniCompose(
        CompletableFuture<S> a,
        Function<? super S, ? extends CompletionStage<T>> f,
        UniCompose<S,T> c) {
        Object r; Throwable x;
        if (a == null || (r = a.result) == null || f == null)
            return false;
        tryComplete: if (result == null) {
            if (r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            try {
                if (c != null && !c.claim())
                    return false;
                @SuppressWarnings("unchecked") S s = (S) r;
                CompletableFuture<T> g = f.apply(s).toCompletableFuture();
                if (g.result == null || !uniRelay(g)) {
                    UniRelay<T> copy = new UniRelay<T>(this, g);
                    g.push(copy);
                    copy.tryFire(SYNC);
                    if (result == null)
                        return false;
                }
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <V> CompletableFuture<V> uniComposeStage(
        Executor e, Function<? super T, ? extends CompletionStage<V>> f) {
        if (f == null) throw new NullPointerException();
        Object r; Throwable x;
        if (e == null && (r = result) != null) {
            // try to return function result directly
            if (r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    return new CompletableFuture<V>(encodeThrowable(x, r));
                }
                r = null;
            }
            try {
                @SuppressWarnings("unchecked") T t = (T) r;
                CompletableFuture<V> g = f.apply(t).toCompletableFuture();
                Object s = g.result;
                if (s != null)
                    return new CompletableFuture<V>(encodeRelay(s));
                CompletableFuture<V> d = new CompletableFuture<V>();
                UniRelay<V> copy = new UniRelay<V>(d, g);
                g.push(copy);
                copy.tryFire(SYNC);
                return d;
            } catch (Throwable ex) {
                return new CompletableFuture<V>(encodeThrowable(ex));
            }
        }
        CompletableFuture<V> d = new CompletableFuture<V>();
        UniCompose<T,V> c = new UniCompose<T,V>(e, d, this, f);
        push(c);
        c.tryFire(SYNC);
        return d;
    }

    /* ------------- Two-input Completions -------------- */

    /** A Completion for an action with two sources */
    /**
     * 代表该对象有 2个 source 和一个 depend
     * @param <T>
     * @param <U>
     * @param <V>
     */
    @SuppressWarnings("serial")
    abstract static class BiCompletion<T,U,V> extends UniCompletion<T,V> {
        /**
         * 第二个 source
         */
        CompletableFuture<U> snd; // second source for action
        BiCompletion(Executor executor, CompletableFuture<V> dep,
                     CompletableFuture<T> src, CompletableFuture<U> snd) {
            super(executor, dep, src); this.snd = snd;
        }
    }

    /** A Completion delegating to a BiCompletion */
    /**
     * 实现 Comoletion 的核心方法  内部包含一个 代理对象
     */
    @SuppressWarnings("serial")
    static final class CoCompletion extends Completion {
        /**
         * 代表 二元函数 即传入2个类型返回一个类型
         */
        BiCompletion<?,?,?> base;
        CoCompletion(BiCompletion<?,?,?> base) { this.base = base; }

        /**
         * 按照某种模式来触发任务
         * @param mode SYNC, ASYNC, or NESTED
         * @return
         */
        final CompletableFuture<?> tryFire(int mode) {
            BiCompletion<?,?,?> c; CompletableFuture<?> d;
            // 如果代理对象为null 或者 触发代理对象的结果为null 返回null
            if ((c = base) == null || (d = c.tryFire(mode)) == null)
                return null;
            // 执行一次后就将代理对象置空 并返回执行结果
            base = null; // detach
            return d;
        }

        /**
         * 判断是否可激活
         * @return
         */
        final boolean isLive() {
            BiCompletion<?,?,?> c;
            // 代理对象存在 且 代理对象 依赖的其他任务不为空
            return (c = base) != null && c.dep != null;
        }
    }

    /** Pushes completion to this and b unless both done. */
    final void bipush(CompletableFuture<?> b, BiCompletion<?,?,?> c) {
        if (c != null) {
            Object r;
            while ((r = result) == null && !tryPushStack(c))
                lazySetNext(c, null); // clear on failure
            if (b != null && b != this && b.result == null) {
                Completion q = (r != null) ? c : new CoCompletion(c);
                while (b.result == null && !b.tryPushStack(q))
                    lazySetNext(q, null); // clear on failure
            }
        }
    }

    /** Post-processing after successful BiCompletion tryFire. */
    /**
     * 执行后置函数   这里要注意传入的模式
     * @param a 第一个数据源
     * @param b 第二个数据源
     * @param mode 触发fire 的模式
     * @return
     */
    final CompletableFuture<T> postFire(CompletableFuture<?> a,
                                        CompletableFuture<?> b, int mode) {
        if (b != null && b.stack != null) { // clean second source
            // 代表嵌套模式 或者 还未生成结果 直接清理  注意 要执行的任务 会被分别设置到 a 和 b 的栈顶 这里 应该是 某个任务已经完成了 就需要将该任务从栈结构中剔除
            // 这样设想 a 或者 b 的其中一个完成 就能结束一个动作  动作会在首次就尝试获取result 这时一般是没有的 那么就选择将任务本身 封装成一个Complete 代表一个动作
            // 且同时设置到 a b 中 (b的是被包装过的代理对象 为什么不直接引用一个对象???)  当某个地方 (现在还不知道) 作为触发点 代表生成了result 然后不断向下传播并触发动作
            // 就会触发到 a 节点 或者b 节点 这时调用之前封装的 动作 这里有几种情况 一种是 动作本身 只要求一个future 有结果 比如 orXXX 那么 另一个对象他的栈上就需要将无效的动作
            // 清理掉 比如之前设置进去的 orXXX 动作 而如果 b 是有结果的那个 这里就触发 complete 的后置函数
            // 如果是 andXXX 函数 那么 一般来讲 a b 都是有结果 都会触发 postComplete
            // 在 postComplete 中 传递下去所有的任务都会以嵌套模式执行
            // 首先假设该节点是 d 并且以a 作为 source 触发了 那么 b 的整条分支都不会执行 这时就要清除b上的所有任务
            // 当使用嵌套模式时 这里会将 当前设置在 自身上的action 给移除掉(已经触发了 d 的值就是通过该动作执行的)
            // 而 result 则是 该任务不需要执行了
            if (mode < 0 || b.result == null)
                b.cleanStack();
            else
                // 该节点首先是d 然后 比方 基于 b 来触发了 注意是同步模式下  会不断向下传播 而如果b是最初的源点
                // b 在设置结果后 将所有可达future 的 所有stack 任务全部执行 那么嵌套模式就可以理解为 不想传播
                b.postComplete();
        }
        return postFire(a, mode);
    }

    @SuppressWarnings("serial")
    static final class BiApply<T,U,V> extends BiCompletion<T,U,V> {
        BiFunction<? super T,? super U,? extends V> fn;
        BiApply(Executor executor, CompletableFuture<V> dep,
                CompletableFuture<T> src, CompletableFuture<U> snd,
                BiFunction<? super T,? super U,? extends V> fn) {
            super(executor, dep, src, snd); this.fn = fn;
        }
        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            if ((d = dep) == null ||
                !d.biApply(a = src, b = snd, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; snd = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final <R,S> boolean biApply(CompletableFuture<R> a,
                                CompletableFuture<S> b,
                                BiFunction<? super R,? super S,? extends T> f,
                                BiApply<R,S,T> c) {
        Object r, s; Throwable x;
        if (a == null || (r = a.result) == null ||
            b == null || (s = b.result) == null || f == null)
            return false;
        tryComplete: if (result == null) {
            if (r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            if (s instanceof AltResult) {
                if ((x = ((AltResult)s).ex) != null) {
                    completeThrowable(x, s);
                    break tryComplete;
                }
                s = null;
            }
            try {
                if (c != null && !c.claim())
                    return false;
                @SuppressWarnings("unchecked") R rr = (R) r;
                @SuppressWarnings("unchecked") S ss = (S) s;
                completeValue(f.apply(rr, ss));
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <U,V> CompletableFuture<V> biApplyStage(
        Executor e, CompletionStage<U> o,
        BiFunction<? super T,? super U,? extends V> f) {
        CompletableFuture<U> b;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<V> d = new CompletableFuture<V>();
        if (e != null || !d.biApply(this, b, f, null)) {
            BiApply<T,U,V> c = new BiApply<T,U,V>(e, d, this, b, f);
            bipush(b, c);
            c.tryFire(SYNC);
        }
        return d;
    }

    /**
     * 基于 accpet 动作  accept 本身是接受2个 参数 不返回结果
     * @param <T>
     * @param <U>
     */
    @SuppressWarnings("serial")
    static final class BiAccept<T,U> extends BiCompletion<T,U,Void> {
        /**
         * 该对象用于 连接 2个 source CompletableFuture 和 depend CompletableFuture
         */
        BiConsumer<? super T,? super U> fn;
        BiAccept(Executor executor, CompletableFuture<Void> dep,
                 CompletableFuture<T> src, CompletableFuture<U> snd,
                 BiConsumer<? super T,? super U> fn) {
            super(executor, dep, src, snd); this.fn = fn;
        }

        /**
         * 按照特定模式来触发  分为 同步触发 异步触发 和 嵌套触发
         * @param mode SYNC, ASYNC, or NESTED
         * @return
         */
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            // 如果依赖future == null 或者 执行 biAccept 处理2个 source 返回false (这里应该是处理失败的意思)返回null
            if ((d = dep) == null ||
                    // mode > 0 代表是 异步模式  这时传入null 嵌套模式或者同步模式 都是传入 this
                !d.biAccept(a = src, b = snd, fn, mode > 0 ? null : this))
                return null;
            // 置空代表本对象 isAlive == false
            dep = null; src = null; snd = null; fn = null;
            // 执行后置函数
            return d.postFire(a, b, mode);
        }
    }

    /**
     * 将2个 source 对象 执行给定的函数
     * @param a  source 1
     * @param b  source 2
     * @param f  函数对象
     * @param c
     * @param <R>
     * @param <S>
     * @return
     */
    final <R,S> boolean biAccept(CompletableFuture<R> a,
                                 CompletableFuture<S> b,
                                 BiConsumer<? super R,? super S> f,
                                 BiAccept<R,S> c) {
        Object r, s; Throwable x;
        if (a == null || (r = a.result) == null ||
            b == null || (s = b.result) == null || f == null)
            return false;
        tryComplete: if (result == null) {
            if (r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            if (s instanceof AltResult) {
                if ((x = ((AltResult)s).ex) != null) {
                    completeThrowable(x, s);
                    break tryComplete;
                }
                s = null;
            }
            try {
                if (c != null && !c.claim())
                    return false;
                @SuppressWarnings("unchecked") R rr = (R) r;
                @SuppressWarnings("unchecked") S ss = (S) s;
                f.accept(rr, ss);
                completeNull();
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <U> CompletableFuture<Void> biAcceptStage(
        Executor e, CompletionStage<U> o,
        BiConsumer<? super T,? super U> f) {
        CompletableFuture<U> b;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (e != null || !d.biAccept(this, b, f, null)) {
            BiAccept<T,U> c = new BiAccept<T,U>(e, d, this, b, f);
            bipush(b, c);
            c.tryFire(SYNC);
        }
        return d;
    }

    @SuppressWarnings("serial")
    static final class BiRun<T,U> extends BiCompletion<T,U,Void> {
        Runnable fn;
        BiRun(Executor executor, CompletableFuture<Void> dep,
              CompletableFuture<T> src,
              CompletableFuture<U> snd,
              Runnable fn) {
            super(executor, dep, src, snd); this.fn = fn;
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            if ((d = dep) == null ||
                !d.biRun(a = src, b = snd, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; snd = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final boolean biRun(CompletableFuture<?> a, CompletableFuture<?> b,
                        Runnable f, BiRun<?,?> c) {
        Object r, s; Throwable x;
        if (a == null || (r = a.result) == null ||
            b == null || (s = b.result) == null || f == null)
            return false;
        if (result == null) {
            if (r instanceof AltResult && (x = ((AltResult)r).ex) != null)
                completeThrowable(x, r);
            else if (s instanceof AltResult && (x = ((AltResult)s).ex) != null)
                completeThrowable(x, s);
            else
                try {
                    if (c != null && !c.claim())
                        return false;
                    f.run();
                    completeNull();
                } catch (Throwable ex) {
                    completeThrowable(ex);
                }
        }
        return true;
    }

    private CompletableFuture<Void> biRunStage(Executor e, CompletionStage<?> o,
                                               Runnable f) {
        CompletableFuture<?> b;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (e != null || !d.biRun(this, b, f, null)) {
            BiRun<T,?> c = new BiRun<>(e, d, this, b, f);
            bipush(b, c);
            c.tryFire(SYNC);
        }
        return d;
    }

    @SuppressWarnings("serial")
    static final class BiRelay<T,U> extends BiCompletion<T,U,Void> { // for And
        BiRelay(CompletableFuture<Void> dep,
                CompletableFuture<T> src,
                CompletableFuture<U> snd) {
            super(null, dep, src, snd);
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            if ((d = dep) == null || !d.biRelay(a = src, b = snd))
                return null;
            src = null; snd = null; dep = null;
            return d.postFire(a, b, mode);
        }
    }

    boolean biRelay(CompletableFuture<?> a, CompletableFuture<?> b) {
        Object r, s; Throwable x;
        if (a == null || (r = a.result) == null ||
            b == null || (s = b.result) == null)
            return false;
        if (result == null) {
            if (r instanceof AltResult && (x = ((AltResult)r).ex) != null)
                completeThrowable(x, r);
            else if (s instanceof AltResult && (x = ((AltResult)s).ex) != null)
                completeThrowable(x, s);
            else
                completeNull();
        }
        return true;
    }

    /** Recursively constructs a tree of completions. */
    /**
     * 构建一颗 满足 and 条件的树
     * @param cfs
     * @param lo
     * @param hi
     * @return
     */
    static CompletableFuture<Void> andTree(CompletableFuture<?>[] cfs,
                                           int lo, int hi) {
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (lo > hi) // empty
            d.result = NIL;
        else {
            CompletableFuture<?> a, b;
            int mid = (lo + hi) >>> 1;
            // 每次 递归 lo 和 hi 都接近最前面的 2个元素
            // 极端情况  lo == mid 等于最左边的节点 等于 hi 都是 0
            if ((a = (lo == mid ? cfs[lo] :
                      andTree(cfs, lo, mid))) == null ||
                (b = (lo == hi ? a : (hi == mid+1) ? cfs[hi] :
                      andTree(cfs, mid+1, hi)))  == null)
                throw new NullPointerException();
            // 本次 a 或者 b 结果还没有生成
            // 之后的递归 会将 前面 返回的 d 对象作为 source
            // 当最初的 a 或者 b
            // 这里ab 可能是一个对象 为 a 设置 传播数据的对象  将新对象(d)返回 之后 d 又会作为其他节点的 a 对象
            // 从第一个开始 第一次 将最左节点包装成 d1 之后将 第二个节点包装成d2 之后将 d1d2 包装成d3 之后 第三个节点变成 d4 第4个节点变成d5 再将 d4 d5  变化d6 最后 d3 d6 合并成d 7 并返回
            //             d7
            //            /\
            //         d3   d6
            //        / \  / \
            //      d1  d2 d3 d4
            //     [0][1]  [2]  [3]
            //
            // 这样无论下游哪个数据填充完成 都可以通过 触发 c （b 上挂了c 的代理对象 起到一样的效果） 比如 d1 借由a 的数据填充完毕而触发 之后 唤醒了 d3 (因为d1 相当于是d3 的a)
            // 由于d7 相当于 需要获取到 数组中全部数据 只要有一个 没有设置 biRelay 就会返回false 比如 d1 d2 都完成 会触发d3 的方法 d3 会尝试将数据填充到 d7 这时 d7 需要确保d6的数据已经设置
            // 就会不满足条件
            if (!d.biRelay(a, b)) {
                BiRelay<?,?> c = new BiRelay<>(d, a, b);
                a.bipush(b, c);
                c.tryFire(SYNC);
            }
        }
        return d;
    }

    /* ------------- Projected (Ored) BiCompletions -------------- */

    /** Pushes completion to this and b unless either done. */
    final void orpush(CompletableFuture<?> b, BiCompletion<?,?,?> c) {
        if (c != null) {
            // 当前还没有设置结果的 时候 才有设置的必要
            while ((b == null || b.result == null) && result == null) {
                // 将c 设置到 本对象 也就是a 的栈顶
                if (tryPushStack(c)) {
                    // 存在b 的情况 将 c 包装后设置到b 中
                    if (b != null && b != this && b.result == null) {
                        Completion q = new CoCompletion(c);
                        while (result == null && b.result == null &&
                               !b.tryPushStack(q))
                            lazySetNext(q, null); // clear on failure
                    }
                    break;
                }
                lazySetNext(c, null); // clear on failure
            }
        }
    }

    @SuppressWarnings("serial")
    static final class OrApply<T,U extends T,V> extends BiCompletion<T,U,V> {
        Function<? super T,? extends V> fn;
        OrApply(Executor executor, CompletableFuture<V> dep,
                CompletableFuture<T> src,
                CompletableFuture<U> snd,
                Function<? super T,? extends V> fn) {
            super(executor, dep, src, snd); this.fn = fn;
        }
        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            if ((d = dep) == null ||
                !d.orApply(a = src, b = snd, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; snd = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final <R,S extends R> boolean orApply(CompletableFuture<R> a,
                                          CompletableFuture<S> b,
                                          Function<? super R, ? extends T> f,
                                          OrApply<R,S,T> c) {
        Object r; Throwable x;
        if (a == null || b == null ||
            ((r = a.result) == null && (r = b.result) == null) || f == null)
            return false;
        tryComplete: if (result == null) {
            try {
                if (c != null && !c.claim())
                    return false;
                if (r instanceof AltResult) {
                    if ((x = ((AltResult)r).ex) != null) {
                        completeThrowable(x, r);
                        break tryComplete;
                    }
                    r = null;
                }
                @SuppressWarnings("unchecked") R rr = (R) r;
                completeValue(f.apply(rr));
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <U extends T,V> CompletableFuture<V> orApplyStage(
        Executor e, CompletionStage<U> o,
        Function<? super T, ? extends V> f) {
        CompletableFuture<U> b;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<V> d = new CompletableFuture<V>();
        if (e != null || !d.orApply(this, b, f, null)) {
            OrApply<T,U,V> c = new OrApply<T,U,V>(e, d, this, b, f);
            orpush(b, c);
            c.tryFire(SYNC);
        }
        return d;
    }

    @SuppressWarnings("serial")
    static final class OrAccept<T,U extends T> extends BiCompletion<T,U,Void> {
        Consumer<? super T> fn;
        OrAccept(Executor executor, CompletableFuture<Void> dep,
                 CompletableFuture<T> src,
                 CompletableFuture<U> snd,
                 Consumer<? super T> fn) {
            super(executor, dep, src, snd); this.fn = fn;
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            if ((d = dep) == null ||
                !d.orAccept(a = src, b = snd, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; snd = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final <R,S extends R> boolean orAccept(CompletableFuture<R> a,
                                           CompletableFuture<S> b,
                                           Consumer<? super R> f,
                                           OrAccept<R,S> c) {
        Object r; Throwable x;
        if (a == null || b == null ||
            ((r = a.result) == null && (r = b.result) == null) || f == null)
            return false;
        tryComplete: if (result == null) {
            try {
                if (c != null && !c.claim())
                    return false;
                if (r instanceof AltResult) {
                    if ((x = ((AltResult)r).ex) != null) {
                        completeThrowable(x, r);
                        break tryComplete;
                    }
                    r = null;
                }
                @SuppressWarnings("unchecked") R rr = (R) r;
                f.accept(rr);
                completeNull();
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private <U extends T> CompletableFuture<Void> orAcceptStage(
        Executor e, CompletionStage<U> o, Consumer<? super T> f) {
        CompletableFuture<U> b;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (e != null || !d.orAccept(this, b, f, null)) {
            OrAccept<T,U> c = new OrAccept<T,U>(e, d, this, b, f);
            orpush(b, c);
            c.tryFire(SYNC);
        }
        return d;
    }

    @SuppressWarnings("serial")
    static final class OrRun<T,U> extends BiCompletion<T,U,Void> {
        Runnable fn;
        OrRun(Executor executor, CompletableFuture<Void> dep,
              CompletableFuture<T> src,
              CompletableFuture<U> snd,
              Runnable fn) {
            super(executor, dep, src, snd); this.fn = fn;
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            if ((d = dep) == null ||
                !d.orRun(a = src, b = snd, fn, mode > 0 ? null : this))
                return null;
            dep = null; src = null; snd = null; fn = null;
            return d.postFire(a, b, mode);
        }
    }

    final boolean orRun(CompletableFuture<?> a, CompletableFuture<?> b,
                        Runnable f, OrRun<?,?> c) {
        Object r; Throwable x;
        if (a == null || b == null ||
            ((r = a.result) == null && (r = b.result) == null) || f == null)
            return false;
        if (result == null) {
            try {
                if (c != null && !c.claim())
                    return false;
                if (r instanceof AltResult && (x = ((AltResult)r).ex) != null)
                    completeThrowable(x, r);
                else {
                    f.run();
                    completeNull();
                }
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }

    private CompletableFuture<Void> orRunStage(Executor e, CompletionStage<?> o,
                                               Runnable f) {
        CompletableFuture<?> b;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (e != null || !d.orRun(this, b, f, null)) {
            OrRun<T,?> c = new OrRun<>(e, d, this, b, f);
            orpush(b, c);
            c.tryFire(SYNC);
        }
        return d;
    }

    /**
     * 剩余 2个 数据源 以及一个 要设置结果的 目标对象  默认就是不使用线程池的  那么 什么样的任务需要设置线程池 ???
     * @param <T>
     * @param <U>
     */
    @SuppressWarnings("serial")
    static final class OrRelay<T,U> extends BiCompletion<T,U,Object> { // for Or
        OrRelay(CompletableFuture<Object> dep, CompletableFuture<T> src,
                CompletableFuture<U> snd) {
            super(null, dep, src, snd);
        }
        // 根据模式触发动作
        final CompletableFuture<Object> tryFire(int mode) {
            CompletableFuture<Object> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            // 如果本来就没有 依赖该数据的对象直接返回null  或者 本次尝试获取结果失败 返回null
            if ((d = dep) == null || !d.orRelay(a = src, b = snd))
                return null;
            src = null; snd = null; dep = null;
            return d.postFire(a, b, mode);
        }
    }

    /**
     * 使用指定函数触发
     * @param a
     * @param b
     * @return
     */
    final boolean orRelay(CompletableFuture<?> a, CompletableFuture<?> b) {
        Object r;
        // 节点还没有结果 直接返回
        if (a == null || b == null ||
            ((r = a.result) == null && (r = b.result) == null))
            return false;
        // 如果本结果还没生成 将 a 或者 b 的结果设置到 result 中
        if (result == null)
            completeRelay(r);

        // 只要 a 或者 b 存在结果了 这里就会返回true
        return true;
    }

    /** Recursively constructs a tree of completions. */
    /**
     * 递归构建树结构
     * @param cfs 代表一组待处理的 future 对象
     * @param lo 起点
     * @param hi 数组长度终点
     * @return
     */
    static CompletableFuture<Object> orTree(CompletableFuture<?>[] cfs,
                                            int lo, int hi) {
        CompletableFuture<Object> d = new CompletableFuture<Object>();
        // 确保 起点 <= 终点
        if (lo <= hi) {
            CompletableFuture<?> a, b;
            // 二分法 递归
            int mid = (lo + hi) >>> 1;
            // 如果已经到起点的位置 将值赋予 a  否则将 就是将a 赋值为 递归调用的结果 如果a == null 或者 b == null (b 代表后半部分)
            if ((a = (lo == mid ? cfs[lo] :
                      orTree(cfs, lo, mid))) == null ||
                (b = (lo == hi ? a : (hi == mid+1) ? cfs[hi] :
                      orTree(cfs, mid+1, hi)))  == null)
                throw new NullPointerException();

            // 如果本次 数据还没有结果 就只能 生成对象并 设置到栈顶中 等待结果
            // 注意 这里是调用 d 的 方法 而不是当前对象的 方法 这种套路就是每次都会创建中间对象 而d 的结果一般都是未设置的 也就是等待 a 或者b 的结果有了后 设置到 d 中
            if (!d.orRelay(a, b)) {
                // 该对象 封装了 执行的逻辑 也就是 有结果了就设置 否则 返回null 而执行的时机 却是由上游来决定的
                OrRelay<?,?> c = new OrRelay<>(d, a, b);
                // c 设置到 a 的栈顶 c 的包装对象 设置到 b的栈顶  这样只要a 或者b 的结果生成了 都可以触发对应的动作
                a.orpush(b, c);
                // 这里应该是做检测 如果result 还没 不在意返回值 如果 生成了结果 会调用 postFire
                c.tryFire(SYNC);
            }
        }
        return d;
    }

    /* ------------- Zero-input Async forms -------------- */

    @SuppressWarnings("serial")
    static final class AsyncSupply<T> extends ForkJoinTask<Void>
            implements Runnable, AsynchronousCompletionTask {
        CompletableFuture<T> dep; Supplier<T> fn;
        AsyncSupply(CompletableFuture<T> dep, Supplier<T> fn) {
            this.dep = dep; this.fn = fn;
        }

        public final Void getRawResult() { return null; }
        public final void setRawResult(Void v) {}
        public final boolean exec() { run(); return true; }

        public void run() {
            CompletableFuture<T> d; Supplier<T> f;
            // 确保下游存在
            if ((d = dep) != null && (f = fn) != null) {
                dep = null; fn = null;
                // 只有当结果没有设置的时候才能调用 如果是不包含返回值的 方法在调用一次后 会设置成NIL 也就是还是不能多次调用
                if (d.result == null) {
                    try {
                        // 从f获取结果
                        d.completeValue(f.get());
                    } catch (Throwable ex) {
                        d.completeThrowable(ex);
                    }
                }
                // 触发下游的 函数 也就是Complete.tryFire
                d.postComplete();
            }
        }
    }

    /**
     * 通过  supplier 来初始化 内部的result 可以通过组合其他函数 来实现 响应式  因为该对象在完成时 会触发下游所有的节点
     * @param e
     * @param f
     * @param <U>
     * @return
     */
    static <U> CompletableFuture<U> asyncSupplyStage(Executor e,
                                                     Supplier<U> f) {
        if (f == null) throw new NullPointerException();
        // 新建一个对象作为 接受 f 结果的下游
        CompletableFuture<U> d = new CompletableFuture<U>();
        // 使用线程池执行任务
        e.execute(new AsyncSupply<U>(d, f));
        return d;
    }

    /**
     * 异步任务对象 内部有一个用于输出结果的引用  dep  以及一个行为对象 runnable
     */
    @SuppressWarnings("serial")
    static final class AsyncRun extends ForkJoinTask<Void>
            implements Runnable, AsynchronousCompletionTask {
        CompletableFuture<Void> dep; Runnable fn;
        AsyncRun(CompletableFuture<Void> dep, Runnable fn) {
            this.dep = dep; this.fn = fn;
        }

        public final Void getRawResult() { return null; }
        public final void setRawResult(Void v) {}

        /**
         * 执行逻辑并始终返回true
         * @return
         */
        public final boolean exec() { run(); return true; }

        public void run() {
            CompletableFuture<Void> d; Runnable f;
            /**
             * 这里首先要确保存在下游元素 这样执行 run 才有意义 因为对外展示的 就是这个下游元素
             */
            if ((d = dep) != null && (f = fn) != null) {
                dep = null; fn = null;
                // 当下游元素不存在时
                if (d.result == null) {
                    try {
                        // 执行任务
                        f.run();
                        // run 没有返回值 这里设置成null 难怪需要 NIL 对象 为了分辨出 null 到底是 未设置 还是 本次执行结果就是null
                        d.completeNull();
                    } catch (Throwable ex) {
                        // 如果出现异常了 将result 设置成异常信息
                        d.completeThrowable(ex);
                    }
                }
                // 代表任务完成了 将 d 内部的全部 complete 全部执行
                // 因为可能会设置一个 链式调用 (后面有一堆响应函数) 本任务完成后就代表可以开始执行函数的内容了
                // 这里是嵌套执行 因为该方法内部本来就会传播调用所以不需要再进行 执行子任务的时候继续传播了 (tryFire 同步异步模式都会进行传播)
                d.postComplete();
            }
        }
    }

    /**
     * 将 线程池 和 动作对象包装成 future 对象
     * @param e
     * @param f
     * @return
     */
    static CompletableFuture<Void> asyncRunStage(Executor e, Runnable f) {
        if (f == null) throw new NullPointerException();
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        // 使用线程池执行被封装的异步任务  使得 d 作为 f 行为的dep 对象
        // 这里 一旦 d 本身设置了 结果就可以直接get 了 但是 d 的下游也许还有任务这样还会继续传播
        e.execute(new AsyncRun(d, f));
        return d;
    }

    /* ------------- Signallers -------------- */

    /**
     * Completion for recording and releasing a waiting thread.  This
     * class implements ManagedBlocker to avoid starvation when
     * blocking actions pile up in ForkJoinPools.
     */
    @SuppressWarnings("serial")
    static final class Signaller extends Completion
        implements ForkJoinPool.ManagedBlocker {
        long nanos;                    // wait time if timed
        final long deadline;           // non-zero if timed
        volatile int interruptControl; // > 0: interruptible, < 0: interrupted
        volatile Thread thread;

        Signaller(boolean interruptible, long nanos, long deadline) {
            this.thread = Thread.currentThread();
            this.interruptControl = interruptible ? 1 : 0;
            this.nanos = nanos;
            this.deadline = deadline;
        }
        final CompletableFuture<?> tryFire(int ignore) {
            Thread w; // no need to atomically claim
            if ((w = thread) != null) {
                thread = null;
                LockSupport.unpark(w);
            }
            return null;
        }
        public boolean isReleasable() {
            if (thread == null)
                return true;
            if (Thread.interrupted()) {
                int i = interruptControl;
                interruptControl = -1;
                if (i > 0)
                    return true;
            }
            if (deadline != 0L &&
                (nanos <= 0L || (nanos = deadline - System.nanoTime()) <= 0L)) {
                thread = null;
                return true;
            }
            return false;
        }
        public boolean block() {
            if (isReleasable())
                return true;
            else if (deadline == 0L)
                LockSupport.park(this);
            else if (nanos > 0L)
                LockSupport.parkNanos(this, nanos);
            return isReleasable();
        }
        final boolean isLive() { return thread != null; }
    }

    /**
     * Returns raw result after waiting, or null if interruptible and
     * interrupted.
     * 当尝试获取结果时会转发到该方法
     * get -> waitingGet(true) 代表本任务是可以打断的
     * join -> waitingGet(false) 代表本任务不可打断
     */
    private Object waitingGet(boolean interruptible) {
        Signaller q = null;
        boolean queued = false;
        int spins = -1;
        Object r;
        // 如果 result 是 NIL 是不会进入阻塞的 也就是 会返回 包装对象
        while ((r = result) == null) {

            // 会先尝试一定次数的自旋
            if (spins < 0)
                spins = (Runtime.getRuntime().availableProcessors() > 1) ?
                    1 << 8 : 0; // Use brief spin-wait on multiprocessors
            else if (spins > 0) {
                if (ThreadLocalRandom.nextSecondarySeed() >= 0)
                    --spins;
            }
            else if (q == null)
                q = new Signaller(interruptible, 0L, 0L);
            else if (!queued)
                // 这里将 信号入栈了 而当本对象 在 fjPool 中完成了任务后 get 相当于 就是给首节点设置栈多设置一个节点
                // 比如 a 设置了 关于d 的action 之后又设置 本信号对象  信号对象就会在 栈的更上面
                queued = tryPushStack(q);
            else if (interruptible && q.interruptControl < 0) {
                // 代表是在阻塞状态下被唤醒的 这样调用cleanStack 会将自身 从 stack 结构中移除  也就是本身是打算获取结果的 所以阻塞线程 被唤醒的时候
                // 只能将本次任务置空
                q.thread = null;
                cleanStack();
                return null;
            }
            else if (q.thread != null && result == null) {
                try {
                    // 阻塞本线程  上游完成任务时 会通过递归的方式 触发所有的 tryFire 针对该信号对象就是 唤醒线程
                    ForkJoinPool.managedBlock(q);
                } catch (InterruptedException ie) {
                    q.interruptControl = -1;
                }
            }
        }
        // 这里代表获取到了结果 无论是 按照一定自旋次数后获取到的 还是 通过tryFire 唤醒信号器之后获取到的
        if (q != null) {
            // 如果是通过唤醒信号器 那么将线程置空
            q.thread = null;
            if (q.interruptControl < 0) {
                // 会进入这里吗 不过逻辑和上面是一样的 就是被打断返回null
                // 刚好设置完结果之后被打断了???
                if (interruptible)
                    r = null; // report interruption
                else
                    // 不允许打断的情况 下 会调用打断方法
                    Thread.currentThread().interrupt();
            }
        }
        // 这里会唤醒内层的任务 因为本对象之后的各种 操作都被封装成 节点设置到 栈结构中 这里触发后 会将栈内 之前暂存的动作触发
        postComplete();
        return r;
    }

    /**
     * Returns raw result after waiting, or null if interrupted, or
     * throws TimeoutException on timeout.
     */
    private Object timedGet(long nanos) throws TimeoutException {
        if (Thread.interrupted())
            return null;
        if (nanos <= 0L)
            throw new TimeoutException();
        long d = System.nanoTime() + nanos;
        Signaller q = new Signaller(true, nanos, d == 0L ? 1L : d); // avoid 0
        boolean queued = false;
        Object r;
        // We intentionally don't spin here (as waitingGet does) because
        // the call to nanoTime() above acts much like a spin.
        while ((r = result) == null) {
            if (!queued)
                queued = tryPushStack(q);
            else if (q.interruptControl < 0 || q.nanos <= 0L) {
                q.thread = null;
                cleanStack();
                if (q.interruptControl < 0)
                    return null;
                throw new TimeoutException();
            }
            else if (q.thread != null && result == null) {
                try {
                    ForkJoinPool.managedBlock(q);
                } catch (InterruptedException ie) {
                    q.interruptControl = -1;
                }
            }
        }
        if (q.interruptControl < 0)
            r = null;
        q.thread = null;
        postComplete();
        return r;
    }

    /* ------------- public methods -------------- */

    /**
     * Creates a new incomplete CompletableFuture.
     */
    public CompletableFuture() {
    }

    /**
     * Creates a new complete CompletableFuture with given encoded result.
     */
    private CompletableFuture(Object r) {
        this.result = r;
    }

    /**
     * Returns a new CompletableFuture that is asynchronously completed
     * by a task running in the {@link ForkJoinPool#commonPool()} with
     * the value obtained by calling the given Supplier.
     *
     * @param supplier a function returning the value to be used
     * to complete the returned CompletableFuture
     * @param <U> the function's return type
     * @return the new CompletableFuture
     * supplyAsync 是用来初始化本 future 结果的
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return asyncSupplyStage(asyncPool, supplier);
    }

    /**
     * Returns a new CompletableFuture that is asynchronously completed
     * by a task running in the given executor with the value obtained
     * by calling the given Supplier.
     *
     * @param supplier a function returning the value to be used
     * to complete the returned CompletableFuture
     * @param executor the executor to use for asynchronous execution
     * @param <U> the function's return type
     * @return the new CompletableFuture
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier,
                                                       Executor executor) {
        return asyncSupplyStage(screenExecutor(executor), supplier);
    }

    /**
     * Returns a new CompletableFuture that is asynchronously completed
     * by a task running in the {@link ForkJoinPool#commonPool()} after
     * it runs the given action.
     *
     * @param runnable the action to run before completing the
     * returned CompletableFuture
     * @return the new CompletableFuture
     * 通过传入一个 runnable 并使用线程池 执行任务
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return asyncRunStage(asyncPool, runnable);
    }

    /**
     * Returns a new CompletableFuture that is asynchronously completed
     * by a task running in the given executor after it runs the given
     * action.
     *
     * @param runnable the action to run before completing the
     * returned CompletableFuture
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletableFuture
     * 使用 外部传入的线程池执行 如果不允许使用 fjPool 而传入的又是 FJPool 就替换成一个 简单的线程池
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable,
                                                   Executor executor) {
        return asyncRunStage(screenExecutor(executor), runnable);
    }

    /**
     * Returns a new CompletableFuture that is already completed with
     * the given value.
     *
     * @param value the value
     * @param <U> the type of the value
     * @return the completed CompletableFuture
     * 使用静态方法创建一个 已经设置了结果的 CompletableFuture 对象
     */
    public static <U> CompletableFuture<U> completedFuture(U value) {
        return new CompletableFuture<U>((value == null) ? NIL : value);
    }

    /**
     * Returns {@code true} if completed in any fashion: normally,
     * exceptionally, or via cancellation.
     *
     * @return {@code true} if completed
     */
    public boolean isDone() {
        return result != null;
    }

    /**
     * Waits if necessary for this future to complete, and then
     * returns its result.
     *
     * @return the result value
     * @throws CancellationException if this future was cancelled
     * @throws ExecutionException if this future completed exceptionally
     * @throws InterruptedException if the current thread was interrupted
     * while waiting
     * get 方法内部会委托给 reportGet 来对结果进行评估
     */
    public T get() throws InterruptedException, ExecutionException {
        Object r;
        // 如果 result == null 才会等待 否则 处理 结果 针对没有返回值的方法 会将 result 设置成NIL 然后 在 reportGet 中又会还原成 null
        return reportGet((r = result) == null ? waitingGet(true) : r);
    }

    /**
     * Waits if necessary for at most the given time for this future
     * to complete, and then returns its result, if available.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the result value
     * @throws CancellationException if this future was cancelled
     * @throws ExecutionException if this future completed exceptionally
     * @throws InterruptedException if the current thread was interrupted
     * while waiting
     * @throws TimeoutException if the wait timed out
     */
    public T get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        Object r;
        long nanos = unit.toNanos(timeout);
        return reportGet((r = result) == null ? timedGet(nanos) : r);
    }

    /**
     * Returns the result value when complete, or throws an
     * (unchecked) exception if completed exceptionally. To better
     * conform with the use of common functional forms, if a
     * computation involved in the completion of this
     * CompletableFuture threw an exception, this method throws an
     * (unchecked) {@link CompletionException} with the underlying
     * exception as its cause.
     *
     * @return the result value
     * @throws CancellationException if the computation was cancelled
     * @throws CompletionException if this future completed
     * exceptionally or a completion computation threw an exception
     */
    public T join() {
        Object r;
        return reportJoin((r = result) == null ? waitingGet(false) : r);
    }

    /**
     * Returns the result value (or throws any encountered exception)
     * if completed, else returns the given valueIfAbsent.
     *
     * @param valueIfAbsent the value to return if not completed
     * @return the result value, if completed, else the given valueIfAbsent
     * @throws CancellationException if the computation was cancelled
     * @throws CompletionException if this future completed
     * exceptionally or a completion computation threw an exception
     */
    public T getNow(T valueIfAbsent) {
        Object r;
        return ((r = result) == null) ? valueIfAbsent : reportJoin(r);
    }

    /**
     * If not already completed, sets the value returned by {@link
     * #get()} and related methods to the given value.
     *
     * @param value the result value
     * @return {@code true} if this invocation caused this CompletableFuture
     * to transition to a completed state, else {@code false}
     */
    public boolean complete(T value) {
        boolean triggered = completeValue(value);
        postComplete();
        return triggered;
    }

    /**
     * If not already completed, causes invocations of {@link #get()}
     * and related methods to throw the given exception.
     *
     * @param ex the exception
     * @return {@code true} if this invocation caused this CompletableFuture
     * to transition to a completed state, else {@code false}
     */
    public boolean completeExceptionally(Throwable ex) {
        if (ex == null) throw new NullPointerException();
        boolean triggered = internalComplete(new AltResult(ex));
        postComplete();
        return triggered;
    }

    public <U> CompletableFuture<U> thenApply(
        Function<? super T,? extends U> fn) {
        return uniApplyStage(null, fn);
    }

    public <U> CompletableFuture<U> thenApplyAsync(
        Function<? super T,? extends U> fn) {
        return uniApplyStage(asyncPool, fn);
    }

    public <U> CompletableFuture<U> thenApplyAsync(
        Function<? super T,? extends U> fn, Executor executor) {
        return uniApplyStage(screenExecutor(executor), fn);
    }

    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return uniAcceptStage(null, action);
    }

    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return uniAcceptStage(asyncPool, action);
    }

    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action,
                                                   Executor executor) {
        return uniAcceptStage(screenExecutor(executor), action);
    }

    public CompletableFuture<Void> thenRun(Runnable action) {
        return uniRunStage(null, action);
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return uniRunStage(asyncPool, action);
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action,
                                                Executor executor) {
        return uniRunStage(screenExecutor(executor), action);
    }

    public <U,V> CompletableFuture<V> thenCombine(
        CompletionStage<? extends U> other,
        BiFunction<? super T,? super U,? extends V> fn) {
        return biApplyStage(null, other, fn);
    }

    public <U,V> CompletableFuture<V> thenCombineAsync(
        CompletionStage<? extends U> other,
        BiFunction<? super T,? super U,? extends V> fn) {
        return biApplyStage(asyncPool, other, fn);
    }

    public <U,V> CompletableFuture<V> thenCombineAsync(
        CompletionStage<? extends U> other,
        BiFunction<? super T,? super U,? extends V> fn, Executor executor) {
        return biApplyStage(screenExecutor(executor), other, fn);
    }

    public <U> CompletableFuture<Void> thenAcceptBoth(
        CompletionStage<? extends U> other,
        BiConsumer<? super T, ? super U> action) {
        return biAcceptStage(null, other, action);
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(
        CompletionStage<? extends U> other,
        BiConsumer<? super T, ? super U> action) {
        return biAcceptStage(asyncPool, other, action);
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(
        CompletionStage<? extends U> other,
        BiConsumer<? super T, ? super U> action, Executor executor) {
        return biAcceptStage(screenExecutor(executor), other, action);
    }

    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other,
                                                Runnable action) {
        return biRunStage(null, other, action);
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action) {
        return biRunStage(asyncPool, other, action);
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action,
                                                     Executor executor) {
        return biRunStage(screenExecutor(executor), other, action);
    }

    public <U> CompletableFuture<U> applyToEither(
        CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return orApplyStage(null, other, fn);
    }

    public <U> CompletableFuture<U> applyToEitherAsync(
        CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return orApplyStage(asyncPool, other, fn);
    }

    public <U> CompletableFuture<U> applyToEitherAsync(
        CompletionStage<? extends T> other, Function<? super T, U> fn,
        Executor executor) {
        return orApplyStage(screenExecutor(executor), other, fn);
    }

    public CompletableFuture<Void> acceptEither(
        CompletionStage<? extends T> other, Consumer<? super T> action) {
        return orAcceptStage(null, other, action);
    }

    public CompletableFuture<Void> acceptEitherAsync(
        CompletionStage<? extends T> other, Consumer<? super T> action) {
        return orAcceptStage(asyncPool, other, action);
    }

    public CompletableFuture<Void> acceptEitherAsync(
        CompletionStage<? extends T> other, Consumer<? super T> action,
        Executor executor) {
        return orAcceptStage(screenExecutor(executor), other, action);
    }

    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other,
                                                  Runnable action) {
        return orRunStage(null, other, action);
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action) {
        return orRunStage(asyncPool, other, action);
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action,
                                                       Executor executor) {
        return orRunStage(screenExecutor(executor), other, action);
    }

    public <U> CompletableFuture<U> thenCompose(
        Function<? super T, ? extends CompletionStage<U>> fn) {
        return uniComposeStage(null, fn);
    }

    public <U> CompletableFuture<U> thenComposeAsync(
        Function<? super T, ? extends CompletionStage<U>> fn) {
        return uniComposeStage(asyncPool, fn);
    }

    public <U> CompletableFuture<U> thenComposeAsync(
        Function<? super T, ? extends CompletionStage<U>> fn,
        Executor executor) {
        return uniComposeStage(screenExecutor(executor), fn);
    }

    /**
     * 当该future 执行结束后 调用 action 处理结果 这里分为2种情况  同步/异步
     * 如果是异步情况 会将fun， source 封装成一个 包含线程池的 aciton 对象并添加到 source 的尾部
     * 这时 尝试以同步方式 或者嵌套方式触发 tryFire 都会被阻止 并且会触发异步执行 (这时如果异步执行 a 的结果还没有生成呢???)
     * 如果以同步方式执行 会先判断当前result 是否完成 之后为了不阻塞当前线程也会将任务 封装后设置到 a 后面  之后将下游对象 d 返回
     * @param action the action to perform
     * @return
     */
    public CompletableFuture<T> whenComplete(
            // 这里传入了 下游的动作
        BiConsumer<? super T, ? super Throwable> action) {
        return uniWhenCompleteStage(null, action);
    }

    /**
     * 以异步模式执行 也就是将节点设置到 a 后的首次判断是否线程池执行 之后还是会以同步方式执行
     * @param action the action to perform
     * @return
     */
    public CompletableFuture<T> whenCompleteAsync(
        BiConsumer<? super T, ? super Throwable> action) {
        return uniWhenCompleteStage(asyncPool, action);
    }

    /**
     * 从外部指定线程池
     * @param action the action to perform
     * @param executor the executor to use for asynchronous execution
     * @return
     */
    public CompletableFuture<T> whenCompleteAsync(
        BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return uniWhenCompleteStage(screenExecutor(executor), action);
    }

    /**
     * 使用handle 封装 也就是将 result 或者 exception 转换成 另一个对象
     * @param fn the function to use to compute the value of the
     * returned CompletionStage
     * @param <U>
     * @return
     */
    public <U> CompletableFuture<U> handle(
        BiFunction<? super T, Throwable, ? extends U> fn) {
        return uniHandleStage(null, fn);
    }

    public <U> CompletableFuture<U> handleAsync(
        BiFunction<? super T, Throwable, ? extends U> fn) {
        return uniHandleStage(asyncPool, fn);
    }

    public <U> CompletableFuture<U> handleAsync(
        BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        return uniHandleStage(screenExecutor(executor), fn);
    }

    /**
     * Returns this CompletableFuture.
     *
     * @return this CompletableFuture
     */
    public CompletableFuture<T> toCompletableFuture() {
        return this;
    }

    // not in interface CompletionStage

    /**
     * Returns a new CompletableFuture that is completed when this
     * CompletableFuture completes, with the result of the given
     * function of the exception triggering this CompletableFuture's
     * completion when it completes exceptionally; otherwise, if this
     * CompletableFuture completes normally, then the returned
     * CompletableFuture also completes normally with the same value.
     * Note: More flexible versions of this functionality are
     * available using methods {@code whenComplete} and {@code handle}.
     *
     * @param fn the function to use to compute the value of the
     * returned CompletableFuture if this CompletableFuture completed
     * exceptionally
     * @return the new CompletableFuture
     * 追加 专门处理异常的 handler
     */
    public CompletableFuture<T> exceptionally(
        Function<Throwable, ? extends T> fn) {
        return uniExceptionallyStage(fn);
    }

    /* ------------- Arbitrary-arity constructions -------------- */

    /**
     * Returns a new CompletableFuture that is completed when all of
     * the given CompletableFutures complete.  If any of the given
     * CompletableFutures complete exceptionally, then the returned
     * CompletableFuture also does so, with a CompletionException
     * holding this exception as its cause.  Otherwise, the results,
     * if any, of the given CompletableFutures are not reflected in
     * the returned CompletableFuture, but may be obtained by
     * inspecting them individually. If no CompletableFutures are
     * provided, returns a CompletableFuture completed with the value
     * {@code null}.
     *
     * <p>Among the applications of this method is to await completion
     * of a set of independent CompletableFutures before continuing a
     * program, as in: {@code CompletableFuture.allOf(c1, c2,
     * c3).join();}.
     *
     * @param cfs the CompletableFutures
     * @return a new CompletableFuture that is completed when all of the
     * given CompletableFutures complete
     * @throws NullPointerException if the array or any of its elements are
     * {@code null}
     */
    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        return andTree(cfs, 0, cfs.length - 1);
    }

    /**
     * Returns a new CompletableFuture that is completed when any of
     * the given CompletableFutures complete, with the same result.
     * Otherwise, if it completed exceptionally, the returned
     * CompletableFuture also does so, with a CompletionException
     * holding this exception as its cause.  If no CompletableFutures
     * are provided, returns an incomplete CompletableFuture.
     *
     * @param cfs the CompletableFutures
     * @return a new CompletableFuture that is completed with the
     * result or exception of any of the given CompletableFutures when
     * one completes
     * @throws NullPointerException if the array or any of its elements are
     * {@code null}
     * 在本对象以及传入的一组对象中 只要满足一个生成了结果就返回
     */
    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
        return orTree(cfs, 0, cfs.length - 1);
    }

    /* ------------- Control and status methods -------------- */

    /**
     * If not already completed, completes this CompletableFuture with
     * a {@link CancellationException}. Dependent CompletableFutures
     * that have not already completed will also complete
     * exceptionally, with a {@link CompletionException} caused by
     * this {@code CancellationException}.
     *
     * @param mayInterruptIfRunning this value has no effect in this
     * implementation because interrupts are not used to control
     * processing.
     *
     * @return {@code true} if this task is now cancelled
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = (result == null) &&
            internalComplete(new AltResult(new CancellationException()));
        postComplete();
        return cancelled || isCancelled();
    }

    /**
     * Returns {@code true} if this CompletableFuture was cancelled
     * before it completed normally.
     *
     * @return {@code true} if this CompletableFuture was cancelled
     * before it completed normally
     */
    public boolean isCancelled() {
        Object r;
        return ((r = result) instanceof AltResult) &&
            (((AltResult)r).ex instanceof CancellationException);
    }

    /**
     * Returns {@code true} if this CompletableFuture completed
     * exceptionally, in any way. Possible causes include
     * cancellation, explicit invocation of {@code
     * completeExceptionally}, and abrupt termination of a
     * CompletionStage action.
     *
     * @return {@code true} if this CompletableFuture completed
     * exceptionally
     */
    public boolean isCompletedExceptionally() {
        Object r;
        return ((r = result) instanceof AltResult) && r != NIL;
    }

    /**
     * Forcibly sets or resets the value subsequently returned by
     * method {@link #get()} and related methods, whether or not
     * already completed. This method is designed for use only in
     * error recovery actions, and even in such situations may result
     * in ongoing dependent completions using established versus
     * overwritten outcomes.
     *
     * @param value the completion value
     */
    public void obtrudeValue(T value) {
        result = (value == null) ? NIL : value;
        postComplete();
    }

    /**
     * Forcibly causes subsequent invocations of method {@link #get()}
     * and related methods to throw the given exception, whether or
     * not already completed. This method is designed for use only in
     * error recovery actions, and even in such situations may result
     * in ongoing dependent completions using established versus
     * overwritten outcomes.
     *
     * @param ex the exception
     * @throws NullPointerException if the exception is null
     */
    public void obtrudeException(Throwable ex) {
        if (ex == null) throw new NullPointerException();
        result = new AltResult(ex);
        postComplete();
    }

    /**
     * Returns the estimated number of CompletableFutures whose
     * completions are awaiting completion of this CompletableFuture.
     * This method is designed for use in monitoring system state, not
     * for synchronization control.
     *
     * @return the number of dependent CompletableFutures
     */
    public int getNumberOfDependents() {
        int count = 0;
        for (Completion p = stack; p != null; p = p.next)
            ++count;
        return count;
    }

    /**
     * Returns a string identifying this CompletableFuture, as well as
     * its completion state.  The state, in brackets, contains the
     * String {@code "Completed Normally"} or the String {@code
     * "Completed Exceptionally"}, or the String {@code "Not
     * completed"} followed by the number of CompletableFutures
     * dependent upon its completion, if any.
     *
     * @return a string identifying this CompletableFuture, as well as its state
     */
    public String toString() {
        Object r = result;
        int count;
        return super.toString() +
            ((r == null) ?
             (((count = getNumberOfDependents()) == 0) ?
              "[Not completed]" :
              "[Not completed, " + count + " dependents]") :
             (((r instanceof AltResult) && ((AltResult)r).ex != null) ?
              "[Completed exceptionally]" :
              "[Completed normally]"));
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long RESULT;
    private static final long STACK;
    private static final long NEXT;
    static {
        try {
            final sun.misc.Unsafe u;
            UNSAFE = u = sun.misc.Unsafe.getUnsafe();
            Class<?> k = CompletableFuture.class;
            RESULT = u.objectFieldOffset(k.getDeclaredField("result"));
            STACK = u.objectFieldOffset(k.getDeclaredField("stack"));
            NEXT = u.objectFieldOffset
                (Completion.class.getDeclaredField("next"));
        } catch (Exception x) {
            throw new Error(x);
        }
    }
}
