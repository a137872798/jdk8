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

import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 * @author Doug Lea
 * @since 1.5
 * 注意该对象对应的是  任务 -- 任务具有自己的状态 (可打断 可获取结果 在完成时可执行对应的回调)
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     * <p>
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL     代表正常完成任务
     * NEW -> COMPLETING -> EXCEPTIONAL     代表出现了异常
     * NEW -> CANCELLED                 代表该任务被关闭
     * NEW -> INTERRUPTING -> INTERRUPTED          代表该任务被其他线程打断
     */
    private volatile int state;
    private static final int NEW = 0;  // 初始状态
    private static final int COMPLETING = 1; // 执行中
    private static final int NORMAL = 2; // 代表正常完成
    private static final int EXCEPTIONAL = 3;//  代表出现了异常
    private static final int CANCELLED = 4;// 任务被关闭
    private static final int INTERRUPTING = 5;// 打断中??
    private static final int INTERRUPTED = 6;// 已经被打断

    /**
     * The underlying callable; nulled out after running
     * 该对象 对应着任务的 执行逻辑 不能为null
     */
    private Callable<V> callable;
    /**
     * The result to return or exception to throw from get()
     * 当任务完成时 会将结果设置到该字段中  该字段可能会存放一个异常对象
     */
    private Object outcome; // non-volatile, protected by state reads/writes
    /**
     * The thread running the callable; CASed during run()
     * 代表执行本任务的线程对象
     */
    private volatile Thread runner;
    /**
     * Treiber stack of waiting threads
     * 等待线程被封装成该Node 节点  为什么需要这个对象 照理说该任务应该是由一个线程来完成 也就是对应runner 字段
     */
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     *          代表设置结果  注意返回的字段不是 volatile 修饰 也就是该方法的调用场景肯定是在 CAS 下
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        // 如果是 正常执行 就返回 outcome
        if (s == NORMAL)
            return (V) x;
        // 代表被关闭 就会抛出 被关闭的异常
        if (s >= CANCELLED)
            throw new CancellationException();
        // 代表执行异常
        throw new ExecutionException((Throwable) x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param callable the callable task
     * @throws NullPointerException if the callable is null
     *                              通过一个回调对象来初始化 且 回调对象必须存在
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result   the result to return on successful completion. If
     *                 you don't need a particular result, consider using
     *                 constructions of the form:
     *                 {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     *                              将 runnable 和result 适配成 callable 并设置  callable 的 抽象意味着 执行一个 runnable 并返回一个 result
     *                              该方法的定义意味着 会返回什么样的结果一开始就已经确定了 而基于callable 的初始化 结果由 callable 的结果决定
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * 判断当前状态是否是cancelled
     *
     * @return
     */
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    /**
     * 只要不是 new 就代表已经开始执行了
     *
     * @return
     */
    public boolean isDone() {
        return state != NEW;
    }

    /**
     * 关闭当前任务 注意传入了一个是否打断线程的 boolean 变量
     *
     * @param mayInterruptIfRunning {@code true} if the thread executing this
     *                              task should be interrupted; otherwise, in-progress tasks are allowed
     *                              to complete
     * @return
     * 实际上该方法本身并不能真正关闭某个正在执行的call()方法 只是说给state 设置非正常完成的标识 而如果interrupt 为true 也只是针对该call
     * 中本身就设置了该标识的检查逻辑  不过它可以提前唤醒等待结果的 node 节点
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        // CAS 操作失败 直接返回false
        if (!(state == NEW &&
                UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                        // 如果是 打断线程的 标识 state 没有直接进入被关闭状态  而是进入一个类似中间的状态-- INTERRUPTING
                        mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            // 触发内部保存线程的 interrupt
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        // 该方法会将park状态的线程 解除阻塞
                        t.interrupt();
                } finally { // final state
                    // 当执行任务的线程 完成任务时  该state 修改成 INTERRUPTED (而不是 CANCELLED)
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            // 当完成任务时触发  因为任务被提早关闭了代表本任务已经 finished
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     *                               获取本次执行的结果
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        // 代表任务还没有完成 阻塞当前线程 这里应该就是多线程操作同一对象的场景 一个是外部线程 一个是runner 线程(内部执行任务的线程)
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        // 这里会将 Exception Interrupt comppleting 等状态设置到结果中
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() {
    }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     *          通过CAS 将结果设置到outcome 字段
     *          COMPLETING 原来代表 耗时操作已经完成了在做最后的赋值操作
     */
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            // 这里会唤醒所有等待的node
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     *          设置异常结果
     */
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

    /**
     * 执行task 任务本身
     */
    public void run() {
        // 该任务 是拒绝并发执行的 虽然可以通过多线程同时执行该对象的run() 但是 只有一条线程能 成功设置runnerOffset
        // 其实是要同时满足下面2个条件才能执行  state == NEW && CAS == true
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return;
        try {
            // 任务的逻辑都在 callable 中
            Callable<V> c = callable;
            // 具备执行条件
            if (c != null && state == NEW) {
                V result;
                // 代表是否跑完 call
                boolean ran;
                try {
                    // 该逻辑本身的执行是不会被外部打断的
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    // 代表没有跑完
                    result = null;
                    ran = false;
                    setException(ex);
                }
                if (ran)
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            // 代表可以用其他线程来 执行该run() 了
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            // 如果本次任务是被打断了  就是调用了cancel(interrupt == true)
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    // 这里执行完 call 并没有设置结果
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        // 确保成功执行 也就是没有设置异常 或被中断
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     * 当本次任务被 cancel(true) 时 走该方法
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        // 如果正在打断中 让出CPU 分片 让 打断逻辑执行完
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     * 封装线程并 生成链式结构
     */
    static final class WaitNode {
        /**
         * node 节点内维护的 线程对象
         */
        volatile Thread thread;
        /**
         * 下个节点
         */
        volatile WaitNode next;

        WaitNode() {
            thread = Thread.currentThread();
        }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     * 当任务完成时 触发  唤醒之前waiters 中所有节点
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        // 尝试获取之后等待的 node 节点 这些节点 是 维护 那些尝试获取结果的外部线程
        for (WaitNode q; (q = waiters) != null; ) {
            // 将node 节点置空
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (; ; ) {
                    // 获取节点对应的线程对象
                    Thread t = q.thread;

                    if (t != null) {
                        // 设置成 null 时 队列还是存在的 只是当thread属性设置为null时 removeNode 方法 会将这些next指针置空
                        q.thread = null;
                        // 如果当前线程处于阻塞状态 唤醒线程   这样 等待本次结果的 线程就可以正常处理了
                        LockSupport.unpark(t);
                    }
                    // 获取下个节点 同时帮助GC 回收
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        // 处理完成任务后的逻辑  默认是空实现
        done();

        // 当作一种清理任务
        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     * 阻塞尝试获取结果的某个线程 应该就是借助 wait() 方法 并且 内部线程(既执行本任务的线程) 完成任务时会从内侧唤醒阻塞线程
     * timed 代表是否定时  nanos 代表 时限
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        // 生成唤醒时间
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        // WaitNode 针对的就是 当前尝试获取任务并被阻塞的线程对象
        WaitNode q = null;
        // 是否入队 默认为false
        boolean queued = false;
        for (; ; ) {
            // 调用awaitDone 的是  获取结果的线程
            // 这里 一般是 代表 从 LockSupport 的 阻塞中醒来时  可能是由外部线程中断的
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            // 只有在New 的情况下 线程才会入队列 应该是推测到 runnable 本身不会是耗时操作 所以没有添加到 WaitNode队列中

            // 获取任务当前状态
            int s = state;
            // 代表完成 (包含正常完成,出现异常和被中断和被关闭)  调用cancel 时  也会走这里
            if (s > COMPLETING) {
                // 如果 thread 属性被设置成 null 之后会在 removeWaiter 中移除该节点
                if (q != null)
                    q.thread = null;
                return s;
                // 代表正在执行中
            } else if (s == COMPLETING) // cannot time out yet
                // 让出CPU 分片
                Thread.yield();

                // 上面2种情况都代表 任务在开始之后
            else if (q == null)
                // 将尝试获取任务的本线程包装成 node 对象
                q = new WaitNode();
                // 代表还未入队列的情况
            else if (!queued)
                // 将结果设置到下个节点
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        q.next = waiters, q);
                // 代表存在超时属性
            else if (timed) {
                nanos = deadline - System.nanoTime();
                // 已经过了 deadLine 直接移出队列
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                // 设定时间 暂停线程
                LockSupport.parkNanos(this, nanos);
            } else
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     * 将节点从 当前等待队列中移除 标准的链表操作
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (; ; ) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    // next 节点通过 volatile 修饰   到这一步可以确定 s 是最新值 但是之后的操作如何确定是否是最新值呢
                    s = q.next;
                    // q不为空代表本次 该节点还是有效的 这时使用一个 pred引用 意味着之后进入下次循环时 该引用保存了上次的节点
                    // 比如 当前 pred 代表上个 q 而这次循环的q (实际上就是将q 指向了 q.next) 无效时进入第二个分支 也就是 thread == null
                    if (q.thread != null)
                        // 该引用记录的是上次循环的节点
                        pred = q;

                        // 判断本次循环的上次q 节点是否为null
                    else if (pred != null) {
                        // 这里的意思是 从队列中 将 某些 thread 已经置空的节点从队列中移除了 pred -> q -> s ==> pred -> s
                        // pred 一开始就是指向 q 的引用 现在更新了next 后 相当于 那个 q之后的 节点由于无对象指向它 会被GC  回收
                        pred.next = s;
                        // 因为之前能设置 pred = q 的条件就是 q.thread != null 这样代表 pred 节点本身已经失效了
                        // 这里需要注意 在什么情况下 thread会设置成 null
                        if (pred.thread == null) // check for race
                            continue retry;
                        // 因为 pred 只要 第一个q.thread 不为空 总会有值 所以只有可能是 第一个节点 本身就有问题  所以就舍弃第一个节点 直接使用 s 作为首节点
                    } else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;

    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
