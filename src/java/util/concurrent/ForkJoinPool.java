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

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import java.security.Permissions;

/**
 * An {@link ExecutorService} for running {@link ForkJoinTask}s.
 * A {@code ForkJoinPool} provides the entry point for submissions
 * from non-{@code ForkJoinTask} clients, as well as management and
 * monitoring operations.
 * <p>
 * forkjoin 线程池不同于 普通的线程池 具备 工作窃取功能
 * <p>A {@code ForkJoinPool} differs from other kinds of {@link
 * ExecutorService} mainly by virtue of employing
 * 线程池中每条线程会尝试拉取由其他线程创建的任务
 * <em>work-stealing</em>: all threads in the pool attempt to find and
 * execute tasks submitted to the pool and/or created by other active
 * 当没有任务时就会在阻塞队列的拉取中等待
 * tasks (eventually blocking waiting for work if none exist). This
 * 在可以通过外部线程添加任务的线程池中 这是非常高效的
 * enables efficient processing when most tasks spawn other subtasks
 * (as do most {@code ForkJoinTask}s), as well as when many small
 * tasks are submitted to the pool from external clients.  Especially
 * 尤其在使用异步模式进行初始化时
 * when setting <em>asyncMode</em> to true in constructors, {@code
 * ForkJoinPool}s may also be appropriate for use with event-style
 * tasks that are never joined.
 * <p>
 * 通过静态变量获取的公共线程池 适用于大多数环境
 * <p>A static {@link #commonPool()} is available and appropriate for
 * most applications. The common pool is used by any ForkJoinTask that
 * is not explicitly submitted to a specified pool. Using the common
 * 使用静态的 公共线程池有助于资源的节约  在common 中线程 在没有使用的时候会缓慢的回收
 * pool normally reduces resource usage (its threads are slowly
 * reclaimed during periods of non-use, and reinstated upon subsequent
 * use).
 * <p>
 * 针对某些应该需要自己定制线程池
 * <p>For applications that require separate or custom pools, a {@code
 * 可以指定线程池的并行级别
 * ForkJoinPool} may be constructed with a given target parallelism
 * 默认情况下并行数 与available processor 有关
 * level; by default, equal to the number of available processors.
 * 线程池试图去维持足够的可用线程  不足时就添加 或者恢复内部暂停的线程(那些线程可能正阻塞在拉取其他线程的任务)
 * The pool attempts to maintain enough active (or available) threads
 * by dynamically adding, suspending, or resuming internal worker
 * threads, even if some tasks are stalled waiting to join others.
 * However, no such adjustments are guaranteed in the face of blocked
 * I/O or other unmanaged synchronization. The nested {@link
 * ManagedBlocker} interface enables extension of the kinds of
 * synchronization accommodated.
 *
 * <p>In addition to execution and lifecycle control methods, this
 * class provides status check methods (for example
 * {@link #getStealCount}) that are intended to aid in developing,
 * tuning, and monitoring fork/join applications. Also, method
 * {@link #toString} returns indications of pool state in a
 * convenient form for informal monitoring.
 *
 * <p>As is the case with other ExecutorServices, there are three
 * 该线程池存在3 种主要的 提交任务的 方法
 * main task execution methods summarized in the following table.
 * These are designed to be used primarily by clients not already
 * engaged in fork/join computations in the current pool.  The main
 * 这些执行的方法 接受 forkjointask 作为参数实例
 * forms of these methods accept instances of {@code ForkJoinTask},
 * 重载的形式 也允许传入 runable 和 callable
 * but overloaded forms also allow mixed execution of plain {@code
 * Runnable}- or {@code Callable}- based activities as well.  However,
 * tasks that are already executing in a pool should normally instead
 * use the within-computation forms listed in the table unless using
 * async event-style tasks that are not usually joined, in which case
 * there is little difference among choice of methods.
 * <p>
 * <table BORDER CELLPADDING=3 CELLSPACING=1>
 * <caption>Summary of task execution methods</caption>
 * <tr>
 * <td></td>
 * <td ALIGN=CENTER> <b>Call from non-fork/join clients</b></td>
 * <td ALIGN=CENTER> <b>Call from within fork/join computations</b></td>
 * </tr>
 * <tr>
 * 执行并异步获取结果
 * <td> <b>Arrange async execution</b></td>
 * <td> {@link #execute(ForkJoinTask)}</td>
 * <td> {@link ForkJoinTask#fork}</td>
 * </tr>
 * <tr>
 * 执行并阻塞获取结果
 * <td> <b>Await and obtain result</b></td>
 * <td> {@link #invoke(ForkJoinTask)}</td>
 * <td> {@link ForkJoinTask#invoke}</td>
 * </tr>
 * <tr>
 * 执行并返回一个future 对象
 * <td> <b>Arrange exec and obtain Future</b></td>
 * <td> {@link #submit(ForkJoinTask)}</td>
 * <td> {@link ForkJoinTask#fork} (ForkJoinTasks <em>are</em> Futures)</td>
 * </tr>
 * </table>
 *
 * <p>The common pool is by default constructed with default
 * parameters, but these may be controlled by setting three
 * {@linkplain System#getProperty system properties}:
 * <ul>
 * <li>{@code java.util.concurrent.ForkJoinPool.common.parallelism}
 * - the parallelism level, a non-negative integer
 * <li>{@code java.util.concurrent.ForkJoinPool.common.threadFactory}
 * - the class name of a {@link ForkJoinWorkerThreadFactory}
 * <li>{@code java.util.concurrent.ForkJoinPool.common.exceptionHandler}
 * - the class name of a {@link UncaughtExceptionHandler}
 * </ul>
 * If a {@link SecurityManager} is present and no factory is
 * specified, then the default pool uses a factory supplying
 * threads that have no {@link Permissions} enabled.
 * The system class loader is used to load these classes.
 * Upon any error in establishing these settings, default parameters
 * are used. It is possible to disable or limit the use of threads in
 * the common pool by setting the parallelism property to zero, and/or
 * using a factory that may return {@code null}. However doing so may
 * cause unjoined tasks to never be executed.
 *
 * <p><b>Implementation notes</b>: This implementation restricts the
 * maximum number of running threads to 32767. Attempts to create
 * pools with greater than the maximum number result in
 * {@code IllegalArgumentException}.
 *
 * <p>This implementation rejects submitted tasks (that is, by throwing
 * {@link RejectedExecutionException}) only when the pool is shut down
 * or internal resources have been exhausted.
 *
 * @author Doug Lea
 * forkJoin 的专用线程池
 * @since 1.7
 */
@sun.misc.Contended
public class ForkJoinPool extends AbstractExecutorService {

    /**
     * 实现概述
     * Implementation Overview
     *
     * 该类以及嵌套的内部类是为了控制内部线程
     * This class and its nested classes provide the main
     * functionality and control for a set of worker threads:
     * 从 非forkjoin 线程提交的任务会进入 提交队列
     * Submissions from non-FJ threads enter into submission queues.
     * worker 应该就是包装了的 forkjoin线程对象 他们会获取这些任务并进行拆分  这些子任务又可能会被其他线程 获取
     * Workers take these tasks and typically split them into subtasks
     * that may be stolen by other workers.  Preference rules give
     * 偏向规则会给予 维护队列本身的线程最高的优先级
     * first priority to processing tasks from their own queues (LIFO
     * 之后以随机的方式从其他队列中偷取任务
     * or FIFO, depending on mode), then to randomized FIFO steals of
     * tasks in other queues.  This framework began as vehicle for
     * 该框架支持以树的结构并行偷取任务
     * supporting tree-structured parallelism using work-stealing.
     * Over time, its scalability advantages led to extensions and
     * changes to better support more diverse usage contexts.  Because
     * most internal methods and nested classes are interrelated,
     * their main rationale and descriptions are presented here;
     * individual methods and nested classes contain only brief
     * comments about details.
     *
     * WorkQueues
     * ==========
     *
     * 工作队列是一个双端队列 它们支持3种操作 push pop poll
     * Most operations occur within work-stealing queues (in nested
     * class WorkQueue).  These are special forms of Deques that
     * support only three of the four possible end-operations -- push,
     * pop, and poll (aka steal), under the further constraints that
     * push 和 pop 只允许任务队列归属的线程访问  poll可能会被其他线程调用
     * push and pop are called only from the owning thread (or, as
     * extended here, under a lock), while poll may be called from
     * other threads.  (If you are unfamiliar with them, you probably
     * want to read Herlihy and Shavit's book "The Art of
     * Multiprocessor programming", chapter 16 describing these in
     * more detail before proceeding.)  The main work-stealing queue
     * design is roughly similar to those in the papers "Dynamic
     * Circular Work-Stealing Deque" by Chase and Lev, SPAA 2005
     * (http://research.sun.com/scalable/pubs/index.html) and
     * "Idempotent work stealing" by Michael, Saraswat, and Vechev,
     * PPoPP 2009 (http://portal.acm.org/citation.cfm?id=1504186).
     * The main differences ultimately stem from GC requirements that
     * we null out taken slots as soon as we can, to maintain as small
     * a footprint as possible even in programs generating huge
     * numbers of tasks. To accomplish this, we shift the CAS
     * arbitrating pop vs poll (steal) from being on the indices
     * ("base" and "top") to the slots themselves.
     *
     * Adding tasks then takes the form of a classic array push(task):
     *    q.array[q.top] = task; ++q.top;
     *
     * (The actual code needs to null-check and size-check the array,
     * properly fence the accesses, and possibly signal waiting
     * workers to start scanning -- see below.)  Both a successful pop
     * and poll mainly entail a CAS of a slot from non-null to null.
     *
     * The pop operation (always performed by owner) is:
     *   if ((base != top) and
     *        (the task at top slot is not null) and
     *        (CAS slot to null))
     *           decrement top and return task;
     *
     * And the poll operation (usually by a stealer) is
     *    if ((base != top) and
     *        (the task at base slot is not null) and
     *        (base has not changed) and
     *        (CAS slot to null))
     *           increment base and return task;
     *
     * Because we rely on CASes of references, we do not need tag bits
     * on base or top.  They are simple ints as used in any circular
     * array-based queue (see for example ArrayDeque).  Updates to the
     * indices guarantee that top == base means the queue is empty,
     * but otherwise may err on the side of possibly making the queue
     * appear nonempty when a push, pop, or poll have not fully
     * committed. (Method isEmpty() checks the case of a partially
     * completed removal of the last element.)  Because of this, the
     * poll operation, considered individually, is not wait-free. One
     * thief cannot successfully continue until another in-progress
     * one (or, if previously empty, a push) completes.  However, in
     * the aggregate, we ensure at least probabilistic
     * non-blockingness.  If an attempted steal fails, a thief always
     * chooses a different random victim target to try next. So, in
     * order for one thief to progress, it suffices for any
     * in-progress poll or new push on any empty queue to
     * complete. (This is why we normally use method pollAt and its
     * variants that try once at the apparent base index, else
     * consider alternative actions, rather than method poll, which
     * retries.)
     *
     * This approach also enables support of a user mode in which
     * local task processing is in FIFO, not LIFO order, simply by
     * using poll rather than pop.  This can be useful in
     * message-passing frameworks in which tasks are never joined.
     * However neither mode considers affinities, loads, cache
     * localities, etc, so rarely provide the best possible
     * performance on a given machine, but portably provide good
     * throughput by averaging over these factors.  Further, even if
     * we did try to use such information, we do not usually have a
     * basis for exploiting it.  For example, some sets of tasks
     * profit from cache affinities, but others are harmed by cache
     * pollution effects. Additionally, even though it requires
     * scanning, long-term throughput is often best using random
     * selection rather than directed selection policies, so cheap
     * randomization of sufficient quality is used whenever
     * applicable.  Various Marsaglia XorShifts (some with different
     * shift constants) are inlined at use points.
     *
     * WorkQueues are also used in a similar way for tasks submitted
     * to the pool. We cannot mix these tasks in the same queues used
     * by workers. Instead, we randomly associate submission queues
     * with submitting threads, using a form of hashing.  The
     * ThreadLocalRandom probe value serves as a hash code for
     * choosing existing queues, and may be randomly repositioned upon
     * contention with other submitters.  In essence, submitters act
     * like workers except that they are restricted to executing local
     * tasks that they submitted (or in the case of CountedCompleters,
     * others with the same root task).  Insertion of tasks in shared
     * mode requires a lock (mainly to protect in the case of
     * resizing) but we use only a simple spinlock (using field
     * qlock), because submitters encountering a busy queue move on to
     * try or create other queues -- they block only when creating and
     * registering new queues. Additionally, "qlock" saturates to an
     * unlockable value (-1) at shutdown. Unlocking still can be and
     * is performed by cheaper ordered writes of "qlock" in successful
     * cases, but uses CAS in unsuccessful cases.
     *
     * Management
     * ==========
     *
     * The main throughput advantages of work-stealing stem from
     * decentralized control -- workers mostly take tasks from
     * themselves or each other, at rates that can exceed a billion
     * per second.  The pool itself creates, activates (enables
     * scanning for and running tasks), deactivates, blocks, and
     * terminates threads, all with minimal central information.
     * There are only a few properties that we can globally track or
     * maintain, so we pack them into a small number of variables,
     * often maintaining atomicity without blocking or locking.
     * Nearly all essentially atomic control state is held in two
     * volatile variables that are by far most often read (not
     * written) as status and consistency checks. (Also, field
     * "config" holds unchanging configuration state.)
     *
     * Field "ctl" contains 64 bits holding information needed to
     * atomically decide to add, inactivate, enqueue (on an event
     * queue), dequeue, and/or re-activate workers.  To enable this
     * packing, we restrict maximum parallelism to (1<<15)-1 (which is
     * far in excess of normal operating range) to allow ids, counts,
     * and their negations (used for thresholding) to fit into 16bit
     * subfields.
     *
     * Field "runState" holds lockable state bits (STARTED, STOP, etc)
     * also protecting updates to the workQueues array.  When used as
     * a lock, it is normally held only for a few instructions (the
     * only exceptions are one-time array initialization and uncommon
     * resizing), so is nearly always available after at most a brief
     * spin. But to be extra-cautious, after spinning, method
     * awaitRunStateLock (called only if an initial CAS fails), uses a
     * wait/notify mechanics on a builtin monitor to block when
     * (rarely) needed. This would be a terrible idea for a highly
     * contended lock, but most pools run without the lock ever
     * contending after the spin limit, so this works fine as a more
     * conservative alternative. Because we don't otherwise have an
     * internal Object to use as a monitor, the "stealCounter" (an
     * AtomicLong) is used when available (it too must be lazily
     * initialized; see externalSubmit).
     *
     * Usages of "runState" vs "ctl" interact in only one case:
     * deciding to add a worker thread (see tryAddWorker), in which
     * case the ctl CAS is performed while the lock is held.
     *
     * Recording WorkQueues.  WorkQueues are recorded in the
     * "workQueues" array. The array is created upon first use (see
     * externalSubmit) and expanded if necessary.  Updates to the
     * array while recording new workers and unrecording terminated
     * ones are protected from each other by the runState lock, but
     * the array is otherwise concurrently readable, and accessed
     * directly. We also ensure that reads of the array reference
     * itself never become too stale. To simplify index-based
     * operations, the array size is always a power of two, and all
     * readers must tolerate null slots. Worker queues are at odd
     * indices. Shared (submission) queues are at even indices, up to
     * a maximum of 64 slots, to limit growth even if array needs to
     * expand to add more workers. Grouping them together in this way
     * simplifies and speeds up task scanning.
     *
     * All worker thread creation is on-demand, triggered by task
     * submissions, replacement of terminated workers, and/or
     * compensation for blocked workers. However, all other support
     * code is set up to work with other policies.  To ensure that we
     * do not hold on to worker references that would prevent GC, All
     * accesses to workQueues are via indices into the workQueues
     * array (which is one source of some of the messy code
     * constructions here). In essence, the workQueues array serves as
     * a weak reference mechanism. Thus for example the stack top
     * subfield of ctl stores indices, not references.
     *
     * Queuing Idle Workers. Unlike HPC work-stealing frameworks, we
     * cannot let workers spin indefinitely scanning for tasks when
     * none can be found immediately, and we cannot start/resume
     * workers unless there appear to be tasks available.  On the
     * other hand, we must quickly prod them into action when new
     * tasks are submitted or generated. In many usages, ramp-up time
     * to activate workers is the main limiting factor in overall
     * performance, which is compounded at program start-up by JIT
     * compilation and allocation. So we streamline this as much as
     * possible.
     *
     * The "ctl" field atomically maintains active and total worker
     * counts as well as a queue to place waiting threads so they can
     * be located for signalling. Active counts also play the role of
     * quiescence indicators, so are decremented when workers believe
     * that there are no more tasks to execute. The "queue" is
     * actually a form of Treiber stack.  A stack is ideal for
     * activating threads in most-recently used order. This improves
     * performance and locality, outweighing the disadvantages of
     * being prone to contention and inability to release a worker
     * unless it is topmost on stack.  We park/unpark workers after
     * pushing on the idle worker stack (represented by the lower
     * 32bit subfield of ctl) when they cannot find work.  The top
     * stack state holds the value of the "scanState" field of the
     * worker: its index and status, plus a version counter that, in
     * addition to the count subfields (also serving as version
     * stamps) provide protection against Treiber stack ABA effects.
     *
     * Field scanState is used by both workers and the pool to manage
     * and track whether a worker is INACTIVE (possibly blocked
     * waiting for a signal), or SCANNING for tasks (when neither hold
     * it is busy running tasks).  When a worker is inactivated, its
     * scanState field is set, and is prevented from executing tasks,
     * even though it must scan once for them to avoid queuing
     * races. Note that scanState updates lag queue CAS releases so
     * usage requires care. When queued, the lower 16 bits of
     * scanState must hold its pool index. So we place the index there
     * upon initialization (see registerWorker) and otherwise keep it
     * there or restore it when necessary.
     *
     * Memory ordering.  See "Correct and Efficient Work-Stealing for
     * Weak Memory Models" by Le, Pop, Cohen, and Nardelli, PPoPP 2013
     * (http://www.di.ens.fr/~zappa/readings/ppopp13.pdf) for an
     * analysis of memory ordering requirements in work-stealing
     * algorithms similar to the one used here.  We usually need
     * stronger than minimal ordering because we must sometimes signal
     * workers, requiring Dekker-like full-fences to avoid lost
     * signals.  Arranging for enough ordering without expensive
     * over-fencing requires tradeoffs among the supported means of
     * expressing access constraints. The most central operations,
     * taking from queues and updating ctl state, require full-fence
     * CAS.  Array slots are read using the emulation of volatiles
     * provided by Unsafe.  Access from other threads to WorkQueue
     * base, top, and array requires a volatile load of the first of
     * any of these read.  We use the convention of declaring the
     * "base" index volatile, and always read it before other fields.
     * The owner thread must ensure ordered updates, so writes use
     * ordered intrinsics unless they can piggyback on those for other
     * writes.  Similar conventions and rationales hold for other
     * WorkQueue fields (such as "currentSteal") that are only written
     * by owners but observed by others.
     *
     * Creating workers. To create a worker, we pre-increment total
     * count (serving as a reservation), and attempt to construct a
     * ForkJoinWorkerThread via its factory. Upon construction, the
     * new thread invokes registerWorker, where it constructs a
     * WorkQueue and is assigned an index in the workQueues array
     * (expanding the array if necessary). The thread is then
     * started. Upon any exception across these steps, or null return
     * from factory, deregisterWorker adjusts counts and records
     * accordingly.  If a null return, the pool continues running with
     * fewer than the target number workers. If exceptional, the
     * exception is propagated, generally to some external caller.
     * Worker index assignment avoids the bias in scanning that would
     * occur if entries were sequentially packed starting at the front
     * of the workQueues array. We treat the array as a simple
     * power-of-two hash table, expanding as needed. The seedIndex
     * increment ensures no collisions until a resize is needed or a
     * worker is deregistered and replaced, and thereafter keeps
     * probability of collision low. We cannot use
     * ThreadLocalRandom.getProbe() for similar purposes here because
     * the thread has not started yet, but do so for creating
     * submission queues for existing external threads.
     *
     * Deactivation and waiting. Queuing encounters several intrinsic
     * races; most notably that a task-producing thread can miss
     * seeing (and signalling) another thread that gave up looking for
     * work but has not yet entered the wait queue.  When a worker
     * cannot find a task to steal, it deactivates and enqueues. Very
     * often, the lack of tasks is transient due to GC or OS
     * scheduling. To reduce false-alarm deactivation, scanners
     * compute checksums of queue states during sweeps.  (The
     * stability checks used here and elsewhere are probabilistic
     * variants of snapshot techniques -- see Herlihy & Shavit.)
     * Workers give up and try to deactivate only after the sum is
     * stable across scans. Further, to avoid missed signals, they
     * repeat this scanning process after successful enqueuing until
     * again stable.  In this state, the worker cannot take/run a task
     * it sees until it is released from the queue, so the worker
     * itself eventually tries to release itself or any successor (see
     * tryRelease).  Otherwise, upon an empty scan, a deactivated
     * worker uses an adaptive local spin construction (see awaitWork)
     * before blocking (via park). Note the unusual conventions about
     * Thread.interrupts surrounding parking and other blocking:
     * Because interrupts are used solely to alert threads to check
     * termination, which is checked anyway upon blocking, we clear
     * status (using Thread.interrupted) before any call to park, so
     * that park does not immediately return due to status being set
     * via some other unrelated call to interrupt in user code.
     *
     * Signalling and activation.  Workers are created or activated
     * only when there appears to be at least one task they might be
     * able to find and execute.  Upon push (either by a worker or an
     * external submission) to a previously (possibly) empty queue,
     * workers are signalled if idle, or created if fewer exist than
     * the given parallelism level.  These primary signals are
     * buttressed by others whenever other threads remove a task from
     * a queue and notice that there are other tasks there as well.
     * On most platforms, signalling (unpark) overhead time is
     * noticeably long, and the time between signalling a thread and
     * it actually making progress can be very noticeably long, so it
     * is worth offloading these delays from critical paths as much as
     * possible. Also, because inactive workers are often rescanning
     * or spinning rather than blocking, we set and clear the "parker"
     * field of WorkQueues to reduce unnecessary calls to unpark.
     * (This requires a secondary recheck to avoid missed signals.)
     *
     * Trimming workers. To release resources after periods of lack of
     * use, a worker starting to wait when the pool is quiescent will
     * time out and terminate (see awaitWork) if the pool has remained
     * quiescent for period IDLE_TIMEOUT, increasing the period as the
     * number of threads decreases, eventually removing all workers.
     * Also, when more than two spare threads exist, excess threads
     * are immediately terminated at the next quiescent point.
     * (Padding by two avoids hysteresis.)
     *
     * Shutdown and Termination. A call to shutdownNow invokes
     * tryTerminate to atomically set a runState bit. The calling
     * thread, as well as every other worker thereafter terminating,
     * helps terminate others by setting their (qlock) status,
     * cancelling their unprocessed tasks, and waking them up, doing
     * so repeatedly until stable (but with a loop bounded by the
     * number of workers).  Calls to non-abrupt shutdown() preface
     * this by checking whether termination should commence. This
     * relies primarily on the active count bits of "ctl" maintaining
     * consensus -- tryTerminate is called from awaitWork whenever
     * quiescent. However, external submitters do not take part in
     * this consensus.  So, tryTerminate sweeps through queues (until
     * stable) to ensure lack of in-flight submissions and workers
     * about to process them before triggering the "STOP" phase of
     * termination. (Note: there is an intrinsic conflict if
     * helpQuiescePool is called when shutdown is enabled. Both wait
     * for quiescence, but tryTerminate is biased to not trigger until
     * helpQuiescePool completes.)
     *
     *
     * Joining Tasks
     * =============
     *
     * Any of several actions may be taken when one worker is waiting
     * to join a task stolen (or always held) by another.  Because we
     * are multiplexing many tasks on to a pool of workers, we can't
     * just let them block (as in Thread.join).  We also cannot just
     * reassign the joiner's run-time stack with another and replace
     * it later, which would be a form of "continuation", that even if
     * possible is not necessarily a good idea since we may need both
     * an unblocked task and its continuation to progress.  Instead we
     * combine two tactics:
     *
     *   Helping: Arranging for the joiner to execute some task that it
     *      would be running if the steal had not occurred.
     *
     *   Compensating: Unless there are already enough live threads,
     *      method tryCompensate() may create or re-activate a spare
     *      thread to compensate for blocked joiners until they unblock.
     *
     * A third form (implemented in tryRemoveAndExec) amounts to
     * helping a hypothetical compensator: If we can readily tell that
     * a possible action of a compensator is to steal and execute the
     * task being joined, the joining thread can do so directly,
     * without the need for a compensation thread (although at the
     * expense of larger run-time stacks, but the tradeoff is
     * typically worthwhile).
     *
     * The ManagedBlocker extension API can't use helping so relies
     * only on compensation in method awaitBlocker.
     *
     * The algorithm in helpStealer entails a form of "linear
     * helping".  Each worker records (in field currentSteal) the most
     * recent task it stole from some other worker (or a submission).
     * It also records (in field currentJoin) the task it is currently
     * actively joining. Method helpStealer uses these markers to try
     * to find a worker to help (i.e., steal back a task from and
     * execute it) that could hasten completion of the actively joined
     * task.  Thus, the joiner executes a task that would be on its
     * own local deque had the to-be-joined task not been stolen. This
     * is a conservative variant of the approach described in Wagner &
     * Calder "Leapfrogging: a portable technique for implementing
     * efficient futures" SIGPLAN Notices, 1993
     * (http://portal.acm.org/citation.cfm?id=155354). It differs in
     * that: (1) We only maintain dependency links across workers upon
     * steals, rather than use per-task bookkeeping.  This sometimes
     * requires a linear scan of workQueues array to locate stealers,
     * but often doesn't because stealers leave hints (that may become
     * stale/wrong) of where to locate them.  It is only a hint
     * because a worker might have had multiple steals and the hint
     * records only one of them (usually the most current).  Hinting
     * isolates cost to when it is needed, rather than adding to
     * per-task overhead.  (2) It is "shallow", ignoring nesting and
     * potentially cyclic mutual steals.  (3) It is intentionally
     * racy: field currentJoin is updated only while actively joining,
     * which means that we miss links in the chain during long-lived
     * tasks, GC stalls etc (which is OK since blocking in such cases
     * is usually a good idea).  (4) We bound the number of attempts
     * to find work using checksums and fall back to suspending the
     * worker and if necessary replacing it with another.
     *
     * Helping actions for CountedCompleters do not require tracking
     * currentJoins: Method helpComplete takes and executes any task
     * with the same root as the task being waited on (preferring
     * local pops to non-local polls). However, this still entails
     * some traversal of completer chains, so is less efficient than
     * using CountedCompleters without explicit joins.
     *
     * Compensation does not aim to keep exactly the target
     * parallelism number of unblocked threads running at any given
     * time. Some previous versions of this class employed immediate
     * compensations for any blocked join. However, in practice, the
     * vast majority of blockages are transient byproducts of GC and
     * other JVM or OS activities that are made worse by replacement.
     * Currently, compensation is attempted only after validating that
     * all purportedly active threads are processing tasks by checking
     * field WorkQueue.scanState, which eliminates most false
     * positives.  Also, compensation is bypassed (tolerating fewer
     * threads) in the most common case in which it is rarely
     * beneficial: when a worker with an empty queue (thus no
     * continuation tasks) blocks on a join and there still remain
     * enough threads to ensure liveness.
     *
     * The compensation mechanism may be bounded.  Bounds for the
     * commonPool (see commonMaxSpares) better enable JVMs to cope
     * with programming errors and abuse before running out of
     * resources to do so. In other cases, users may supply factories
     * that limit thread construction. The effects of bounding in this
     * pool (like all others) is imprecise.  Total worker counts are
     * decremented when threads deregister, not when they exit and
     * resources are reclaimed by the JVM and OS. So the number of
     * simultaneously live threads may transiently exceed bounds.
     *
     * Common Pool
     * ===========
     *
     * The static common pool always exists after static
     * initialization.  Since it (or any other created pool) need
     * never be used, we minimize initial construction overhead and
     * footprint to the setup of about a dozen fields, with no nested
     * allocation. Most bootstrapping occurs within method
     * externalSubmit during the first submission to the pool.
     *
     * When external threads submit to the common pool, they can
     * perform subtask processing (see externalHelpComplete and
     * related methods) upon joins.  This caller-helps policy makes it
     * sensible to set common pool parallelism level to one (or more)
     * less than the total number of available cores, or even zero for
     * pure caller-runs.  We do not need to record whether external
     * submissions are to the common pool -- if not, external help
     * methods return quickly. These submitters would otherwise be
     * blocked waiting for completion, so the extra effort (with
     * liberally sprinkled task status checks) in inapplicable cases
     * amounts to an odd form of limited spin-wait before blocking in
     * ForkJoinTask.join.
     *
     * As a more appropriate default in managed environments, unless
     * overridden by system properties, we use workers of subclass
     * InnocuousForkJoinWorkerThread when there is a SecurityManager
     * present. These workers have no permissions set, do not belong
     * to any user-defined ThreadGroup, and erase all ThreadLocals
     * after executing any top-level task (see WorkQueue.runTask).
     * The associated mechanics (mainly in ForkJoinWorkerThread) may
     * be JVM-dependent and must access particular Thread class fields
     * to achieve this effect.
     *
     * Style notes
     * ===========
     *
     * Memory ordering relies mainly on Unsafe intrinsics that carry
     * the further responsibility of explicitly performing null- and
     * bounds- checks otherwise carried out implicitly by JVMs.  This
     * can be awkward and ugly, but also reflects the need to control
     * outcomes across the unusual cases that arise in very racy code
     * with very few invariants. So these explicit checks would exist
     * in some form anyway.  All fields are read into locals before
     * use, and null-checked if they are references.  This is usually
     * done in a "C"-like style of listing declarations at the heads
     * of methods or blocks, and using inline assignments on first
     * encounter.  Array bounds-checks are usually performed by
     * masking with array.length-1, which relies on the invariant that
     * these arrays are created with positive lengths, which is itself
     * paranoically checked. Nearly all explicit checks lead to
     * bypass/return, not exception throws, because they may
     * legitimately arise due to cancellation/revocation during
     * shutdown.
     *
     * There is a lot of representation-level coupling among classes
     * ForkJoinPool, ForkJoinWorkerThread, and ForkJoinTask.  The
     * fields of WorkQueue maintain data structures managed by
     * ForkJoinPool, so are directly accessed.  There is little point
     * trying to reduce this, since any associated future changes in
     * representations will need to be accompanied by algorithmic
     * changes anyway. Several methods intrinsically sprawl because
     * they must accumulate sets of consistent reads of fields held in
     * local variables.  There are also other coding oddities
     * (including several unnecessary-looking hoisted null checks)
     * that help some methods perform reasonably even when interpreted
     * (not compiled).
     *
     * The order of declarations in this file is (with a few exceptions):
     * (1) Static utility functions
     * (2) Nested (static) classes
     * (3) Static fields
     * (4) Fields, along with constants used when unpacking some of them
     * (5) Internal control methods
     * (6) Callbacks and other support for ForkJoinTask methods
     * (7) Exported methods
     * (8) Static block initializing statics in minimally dependent order
     */

    // Static utilities

    /**
     * If there is a security manager, makes sure caller has
     * permission to modify threads.
     * 权限校验的先不看
     */
    private static void checkPermission() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(modifyThreadPermission);
    }

    // Nested classes

    /**
     * Factory for creating new {@link ForkJoinWorkerThread}s.
     * A {@code ForkJoinWorkerThreadFactory} must be defined and used
     * for {@code ForkJoinWorkerThread} subclasses that extend base
     * functionality or initialize threads with different contexts.
     * forkJoin 专用的线程池工厂
     */
    public static interface ForkJoinWorkerThreadFactory {
        /**
         * Returns a new worker thread operating in the given pool.
         *
         * @param pool the pool this thread works in
         * @return the new worker thread
         * @throws NullPointerException if the pool is null
         */
        public ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    /**
     * Default ForkJoinWorkerThreadFactory implementation; creates a
     * new ForkJoinWorkerThread.
     * 默认的forkjoin 线程工厂
     */
    static final class DefaultForkJoinWorkerThreadFactory
            implements ForkJoinWorkerThreadFactory {
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            // 在初始化线程对象时 还给 pool 生成该线程对应的 workQueue
            return new ForkJoinWorkerThread(pool);
        }
    }

    /**
     * Class for artificial tasks that are used to replace the target
     * of local joins if they are removed from an interior queue slot
     * in WorkQueue.tryRemoveAndExec. We don't need the proxy to
     * actually do anything beyond having a unique identity.
     * 默认的空任务
     */
    static final class EmptyTask extends ForkJoinTask<Void> {
        private static final long serialVersionUID = -7721805057305804111L;

        /**
         * 状态 始终为 normal
         */
        EmptyTask() {
            status = ForkJoinTask.NORMAL;
        } // force done

        public final Void getRawResult() {
            return null;
        }

        /**
         * 当正常执行(没有抛出异常时) 通过调用该方法获取本次执行结果
         *
         * @param x
         */
        public final void setRawResult(Void x) {
        }

        /**
         * 该方法对应执行的任务逻辑
         *
         * @return
         */
        public final boolean exec() {
            return true;
        }
    }

    // Constants shared across ForkJoinPool and WorkQueue  工作队列和线程池 共享的常量

    // Bounds
    /**
     * 最大索引值 也可以理解为 wqs 的最大长度     config 就是 (并行度 % SMASK) | MODE
     */
    static final int SMASK = 0xffff;        // short bits == max index
    /**
     * 最大的工作线程数
     */
    static final int MAX_CAP = 0x7fff;        // max #workers - 1
    /**
     * 低位偶数掩码
     */
    static final int EVENMASK = 0xfffe;        // even short bits
    /**
     * 代表 workQueues 最多有 64 个slot      这个值是127
     */
    static final int SQMASK = 0x007e;        // max 64 (even) slots

    // Masks and units for WorkQueue.scanState and ctl sp subfield
    /**
     * 任务是否正在执行
     */
    static final int SCANNING = 1;             // false when running tasks
    /**
     * 失活  为负数
     */
    static final int INACTIVE = 1 << 31;       // must be negative
    /**
     * 版本信息  防止出现ABA 问题
     */
    static final int SS_SEQ = 1 << 16;       // version count

    // Mode bits for ForkJoinPool.config and WorkQueue.config
    /**
     * 模式掩码
     */
    static final int MODE_MASK = 0xffff << 16;  // top half of int
    /**
     * 代表 使用后进先出
     */
    static final int LIFO_QUEUE = 0;
    /**
     * 先进先出
     */
    static final int FIFO_QUEUE = 1 << 16;
    /**
     * 共享模式队列 负数
     */
    static final int SHARED_QUEUE = 1 << 31;       // must be negative

    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for descriptions and algorithms.
     * Performance on most platforms is very sensitive to placement of
     * instances of both WorkQueues and their arrays -- we absolutely
     * do not want multiple WorkQueue instances or multiple queue
     * arrays sharing cache lines. The @Contended annotation alerts
     * JVMs to try to keep instances apart.
     * <p>
     * we absolutely do not want multiple WorkQueue instances or multiple queue arrays sharing cache lines.
     * 我们不需要这么多工作队列去共享缓存行所以使用了 @Contended 注解
     * 支持工作窃取的队列
     */
    @sun.misc.Contended
    static final class WorkQueue {

        /**
         * Capacity of work-stealing queue array upon initialization.
         * Must be a power of two; at least 4, but should be larger to
         * reduce or eliminate cacheline sharing among queues.
         * Currently, it is much larger, as a partial workaround for
         * the fact that JVMs often place arrays in locations that
         * share GC bookkeeping (especially cardmarks) such that
         * per-write accesses encounter serious memory contention.
         * 初始化的 工作队列大小
         */
        static final int INITIAL_QUEUE_CAPACITY = 1 << 13;

        /**
         * Maximum size for queue arrays. Must be a power of two less
         * than or equal to 1 << (31 - width of array entry) to ensure
         * lack of wraparound of index calculations, but defined to a
         * value a bit less than this to help users trap runaway
         * programs before saturating systems.
         * 队列最大长度
         */
        static final int MAXIMUM_QUEUE_CAPACITY = 1 << 26; // 64M

        // Instance fields
        /**
         * 扫描状态   负数代表失活  基数代表处在扫描状态  如果wq 没有对应的 线程 也就是外部线程创建的 那么该wq 的scanState 始终为负数
         * 如果有自己的 owner 该值初始值为 该wq 在wqs中的下标 同时也肯定是一个奇数   如果是0 代表owner 正在处理某个任务
         */
        volatile int scanState;    // versioned, <0: inactive; odd:scanning
        /**
         * 记录空闲栈中前一个元素
         */
        int stackPred;             // pool stack (ctl) predecessor
        /**
         * 代表被偷取的任务数量
         */
        int nsteals;               // number of steals
        /**
         * 记录偷取者索引  一开始为随机数
         */
        int hint;                  // randomization and stealer index hint
        /**
         * 包含池索引信息 和模式信息  index|mode
         * 下标为偶数 代表是 共享队列中的 就是先进先出  如果有owner 就是先进后出
         */
        int config;                // pool index and mode
        /**
         * 1 代表被锁定 小于0 代表 终止 其他情况就是 等于0   为什么一个 工作队列要有lock 的概念 应该是 为了防止某个任务被多个线程争抢
         */
        volatile int qlock;        // 1: locked, < 0: terminate; else 0
        /**
         * 记录下个会被拉取任务的 槽
         */
        volatile int base;         // index of next slot for poll
        /**
         * 下个会推入的槽
         */
        int top;                   // index of next slot for push
        /**
         * 存放任务的数组
         */
        ForkJoinTask<?>[] array;   // the elements (initially unallocated)
        /**
         * 该 workQueue 所属的线程池
         */
        final ForkJoinPool pool;   // the containing pool (may be null)
        /**
         * 共享模式下为null 有些被状态的 workQueue 就是没有线程的
         */
        final ForkJoinWorkerThread owner; // owning thread or null if shared
        /**
         * 当本 workQueue 调用阻塞方法时  parker 就是 owner 其他时间 就是null
         */
        volatile Thread parker;    // == owner during call to park; else null

        // 这里特地维护了2个指针
        /**
         * 当前正在 等待join 的任务
         */
        volatile ForkJoinTask<?> currentJoin;  // task being joined in awaitJoin
        /**
         * 当前执行 从其他线程偷来的
         */
        volatile ForkJoinTask<?> currentSteal; // mainly used by helpStealer

        /**
         * 使用一个线程池对象和一个工作线程来初始化 forkjoin
         *
         * @param pool
         * @param owner 每个 workQueue 对应一个 forkjoin 线程对象
         */
        WorkQueue(ForkJoinPool pool, ForkJoinWorkerThread owner) {
            this.pool = pool;
            this.owner = owner;
            // Place indices in the center of array (that is not yet allocated)
            // 初始化时 拉取和 推入的起点都是 数组的中间
            base = top = INITIAL_QUEUE_CAPACITY >>> 1;
        }

        /**
         * Returns an exportable index (used by ForkJoinWorkerThread).
         */
        final int getPoolIndex() {
            return (config & 0xffff) >>> 1; // ignore odd/even tag bit
        }

        /**
         * Returns the approximate number of tasks in the queue.
         */
        final int queueSize() {
            // 如果拉取的偏移量 大于 推送的偏移量 肯定就是没有元素了
            int n = base - top;       // non-owner callers must read base first
            return (n >= 0) ? 0 : -n; // ignore transient negative
        }

        /**
         * Provides a more accurate estimate of whether this queue has
         * any tasks than does queueSize, by checking whether a
         * near-empty queue has at least one unclaimed task.
         * 判断当前队列是否还有元素
         */
        final boolean isEmpty() {
            ForkJoinTask<?>[] a;
            int n, m, s;
            // base - top  >= 0 就代表没有元素  可以预见每次插入新元素的时候 top都会向后移动 这样 top!= base 也就代表当前有元素
            return ((n = base - (s = top)) >= 0 ||
                    // n = -1 时代表还有一个任务 这时队列还是有可能判定为空 如果 n < -1 代表有多个任务 同时不可能为空
                    (n == -1 &&           // possibly one task
                            // 数组为空 或者数组长度为0
                            ((a = array) == null || (m = a.length - 1) < 0 ||
                                    // 这里通过CAS 直接使用偏移量找到对应的元素  如果对应的元素不存在 就肯定是 空
                                    U.getObject
                                            // (m & (s - 1) 代表是 top 前一个元素的下标    << ASHIFT 代表将下标转换成元素的大小 ABASE 是数组基础的偏移量
                                                    (a, (long) ((m & (s - 1)) << ASHIFT) + ABASE) == null)));
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.  (The
         * shared-queue version is embedded in method externalPush.)
         *
         * @param task the task. Caller must ensure non-null.
         * @throws RejectedExecutionException if array cannot be resized
         *                                    将元素添加到数组中
         */
        final void push(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a;
            ForkJoinPool p;
            int b = base, s = top, n;
            // 一开始数组都还没初始化
            if ((a = array) != null) {    // ignore if queue removed
                int m = a.length - 1;     // fenced write for task visibility
                // 这里就是通过CAS 直接定位到对应元素的偏移量 之后将 task 设置进去  等价于 直接对 a[s] 进行赋值
                U.putOrderedObject(a, ((m & s) << ASHIFT) + ABASE, task);
                // 更新 top的偏移量
                U.putOrderedInt(this, QTOP, s + 1);
                // 代表 首次插入一个节点这时需要唤醒线程池对象处理该任务队列中的任务
                if ((n = s - b) <= 1) {
                    if ((p = pool) != null)
                        // 唤醒本 工作队列
                        p.signalWork(p.workQueues, this);
                    // 这里代表需要扩容  这里既然用了  mask 就代表是某种轮式算法 top - base 可能会超过一轮 这时就需要扩容
                } else if (n >= m)
                    growArray();
            }
        }

        /**
         * Initializes or doubles the capacity of array. Call either
         * by owner or with lock held -- it is OK for base, but not
         * top, to move while resizings are in progress.
         * 初始化数组对象 如果对象已经初始化就进行扩容
         */
        final ForkJoinTask<?>[] growArray() {
            ForkJoinTask<?>[] oldA = array;
            // 获取尝试数组长度 如果为 null size == 初始化大小
            int size = oldA != null ? oldA.length << 1 : INITIAL_QUEUE_CAPACITY;
            if (size > MAXIMUM_QUEUE_CAPACITY)
                throw new RejectedExecutionException("Queue capacity exceeded");
            int oldMask, t, b;
            // 初始化数组
            ForkJoinTask<?>[] a = array = new ForkJoinTask<?>[size];
            // 如果本次是扩容操作  且 数组长度正常 且 之前有剩余元素(如果没有剩余元素 直接扩容就好了 有的话就要对元素进行迁移)
            if (oldA != null && (oldMask = oldA.length - 1) >= 0 &&
                    (t = top) - (b = base) > 0) {
                // 获取新掩码
                int mask = size - 1;
                do { // emulate poll from old array, push to new array   将任务从旧数组移动到新数组
                    ForkJoinTask<?> x;
                    // oldj 对应的是 旧数组中还没有没有取出的 任务
                    int oldj = ((b & oldMask) << ASHIFT) + ABASE;
                    // 代表新的偏移量
                    int j = ((b & mask) << ASHIFT) + ABASE;
                    // 获取到旧任务 getObjectVolatile 就是在普通操作上额外设置内存屏障
                    x = (ForkJoinTask<?>) U.getObjectVolatile(oldA, oldj);
                    // CAS 将原数组置空成功
                    if (x != null &&
                            U.compareAndSwapObject(oldA, oldj, x, null))
                        // 将对象设置到新数组
                        U.putObjectVolatile(a, j, x);
                } while (++b != t);
            }
            // 首次初始化 直接返回数组对象
            return a;
        }

        /**
         * Takes next task, if one exists, in LIFO order.  Call only
         * by owner in unshared queues.
         * 从队列中弹出任务   注意 pop的顺序是先进后出 所以base没有改变而是 top在减小
         */
        final ForkJoinTask<?> pop() {
            ForkJoinTask<?>[] a;
            ForkJoinTask<?> t;
            int m;
            // 首先确保数组被正确初始化
            if ((a = array) != null && (m = a.length - 1) >= 0) {
                // 确保至少有一个偏差值  这里是  从 top 往base 走 跟push的顺序相反
                for (int s; (s = top - 1) - base >= 0; ) {
                    long j = ((m & s) << ASHIFT) + ABASE;
                    // 如果没有元素 直接返回
                    if ((t = (ForkJoinTask<?>) U.getObject(a, j)) == null)
                        break;
                    // 找到元素 置空数组并返回结果
                    if (U.compareAndSwapObject(a, j, t, null)) {
                        // top - 1
                        U.putOrderedInt(this, QTOP, s);
                        return t;
                    }
                }
            }
            return null;
        }

        /**
         * Takes a task in FIFO order if b is base of queue and a task
         * can be claimed without contention. Specialized versions
         * appear in ForkJoinPool methods scan and helpStealer.
         * 通过指定偏移量的方式 直接弹出对应的元素 (先进先出)  偏移量没有找到对应元素时 返回null
         */
        final ForkJoinTask<?> pollAt(int b) {
            ForkJoinTask<?> t;
            ForkJoinTask<?>[] a;
            if ((a = array) != null) {
                // 通过传入的b 直接找到下标
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                if ((t = (ForkJoinTask<?>) U.getObjectVolatile(a, j)) != null &&
                        base == b && U.compareAndSwapObject(a, j, t, null)) {
                    base = b + 1;
                    return t;
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in FIFO order.
         * 通过先进先出的顺序弹出一个元素
         */
        final ForkJoinTask<?> poll() {
            ForkJoinTask<?>[] a;
            int b;
            ForkJoinTask<?> t;
            while ((b = base) - top < 0 && (a = array) != null) {
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                // 以插入内存屏障的方式获取元素
                t = (ForkJoinTask<?>) U.getObjectVolatile(a, j);
                if (base == b) {
                    if (t != null) {
                        if (U.compareAndSwapObject(a, j, t, null)) {
                            base = b + 1;
                            return t;
                        }
                        // 代表没有元素可取
                    } else if (b + 1 == top) // now empty
                        break;
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in order specified by mode.
         * 根据队列的 类型 (先进先出 还是 先进后出) 执行不同的方法
         */
        final ForkJoinTask<?> nextLocalTask() {
            return (config & FIFO_QUEUE) == 0 ? pop() : poll();
        }

        /**
         * Returns next task, if one exists, in order specified by mode.
         * 根据 队列的类型 查看下一个元素 (不将任务移出队列)
         */
        final ForkJoinTask<?> peek() {
            ForkJoinTask<?>[] a = array;
            int m;
            if (a == null || (m = a.length - 1) < 0)
                return null;
            int i = (config & FIFO_QUEUE) == 0 ? top - 1 : base;
            int j = ((i & m) << ASHIFT) + ABASE;
            return (ForkJoinTask<?>) U.getObjectVolatile(a, j);
        }

        /**
         * Pops the given task only if it is at the current top.
         * (A shared version is available only via FJP.tryExternalUnpush)
         * 只有当 传入的任务对应的 下标为top时 才允许弹出
         */
        final boolean tryUnpush(ForkJoinTask<?> t) {
            ForkJoinTask<?>[] a;
            int s;
            if ((a = array) != null && (s = top) != base &&
                    U.compareAndSwapObject
                            // 因为 top 代表下次要插入的元素下标 所以 --top 就是当前最上面的任务
                                    (a, (((a.length - 1) & --s) << ASHIFT) + ABASE, t, null)) {
                // 如果CAS 成功就修改 top值 并返回成功
                U.putOrderedInt(this, QTOP, s);
                return true;
            }
            return false;
        }

        /**
         * Removes and cancels all known tasks, ignoring any exceptions.
         * 关闭所有任务
         */
        final void cancelAll() {
            ForkJoinTask<?> t;
            // 如果当前 正在join的任务不为空
            if ((t = currentJoin) != null) {
                currentJoin = null;
                ForkJoinTask.cancelIgnoringExceptions(t);
            }
            // 如果当前正在窃取的任务不为空
            if ((t = currentSteal) != null) {
                currentSteal = null;
                ForkJoinTask.cancelIgnoringExceptions(t);
            }
            // 按照先进先出的顺序取出所有任务并关闭
            while ((t = poll()) != null)
                ForkJoinTask.cancelIgnoringExceptions(t);
        }

        // Specialized execution methods

        /**
         * Polls and runs tasks until empty.
         * 取出所有任务并执行
         */
        final void pollAndExecAll() {
            for (ForkJoinTask<?> t; (t = poll()) != null; )
                t.doExec();
        }

        /**
         * Removes and executes all local tasks. If LIFO, invokes
         * pollAndExecAll. Otherwise implements a specialized pop loop
         * to exec until empty.
         * 执行所有 该workQueue 保存的任务 (也就是不从其他workQueue 中拉取任务)
         */
        final void execLocalTasks() {
            int b = base, m, s;
            ForkJoinTask<?>[] a = array;
            if (b - (s = top - 1) <= 0 && a != null &&
                    (m = a.length - 1) >= 0) {
                // 如果是先进后出队列
                if ((config & FIFO_QUEUE) == 0) {
                    for (ForkJoinTask<?> t; ; ) {
                        // 如果已经没有元素可取 退出循环
                        if ((t = (ForkJoinTask<?>) U.getAndSetObject
                                (a, ((m & s) << ASHIFT) + ABASE, null)) == null)
                            break;
                        // 更新top 的值
                        U.putOrderedInt(this, QTOP, s);
                        // 执行任务
                        t.doExec();
                        // 判断是否还有元素
                        if (base - (s = top - 1) > 0)
                            break;
                    }
                } else
                    // 先进先出
                    pollAndExecAll();
            }
        }

        /**
         * Executes the given task and any remaining local tasks.
         * 执行给定的任务 以及剩余的其他任务  task 就是从其他的wq 中偷来的任务
         */
        final void runTask(ForkJoinTask<?> task) {
            if (task != null) {
                // 设置成繁忙状态   scanState == 1 代表 该wq 对象正处在执行任务的情况
                scanState &= ~SCANNING; // mark as busy
                // 执行当前任务 同时 该任务作为 当前窃取任务
                (currentSteal = task).doExec();
                U.putOrderedObject(this, QCURRENTSTEAL, null); // release for GC
                // 执行本地任务
                execLocalTasks();
                ForkJoinWorkerThread thread = owner;
                // 如果当前记录的 偷取任务数量过大 将数据设置到 counter中 并清空当前计数
                if (++nsteals < 0)      // collect on overflow
                    transferStealCount(pool);
                // 将 scanState 恢复成0 代表在扫描状态   0 代表在扫描中也就是默认状态 1 代表正在执行任务 负数代表失活
                scanState |= SCANNING;
                if (thread != null)
                    // 后置钩子 默认是 noop
                    thread.afterTopLevelExec();
            }
        }

        /**
         * Adds steal count to pool stealCounter if it exists, and resets.
         * 就是将 int 值追加到long 上 并清除 原本的 int 引用
         */
        final void transferStealCount(ForkJoinPool p) {
            AtomicLong sc;
            if (p != null && (sc = p.stealCounter) != null) {
                int s = nsteals;
                nsteals = 0;            // if negative, correct for overflow
                sc.getAndAdd((long) (s < 0 ? Integer.MAX_VALUE : s));
            }
        }

        /**
         * If present, removes from queue and executes the given task,
         * or any other cancelled task. Used only by awaitJoin.
         *
         * @return true if queue empty and task not known to be done
         * 如果队列中存在该任务 从队列中移除 并执行
         */
        final boolean tryRemoveAndExec(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a;
            int m, s, b, n;
            if ((a = array) != null && (m = a.length - 1) >= 0 &&
                    task != null) {
                // 当还有元素可取时
                while ((n = (s = top) - (b = base)) > 0) {
                    // 遍历 top 和 base 之间的元素
                    for (ForkJoinTask<?> t; ; ) {      // traverse from s to b
                        // 拉取最顶端的元素
                        long j = ((--s & m) << ASHIFT) + ABASE;
                        // 如果当前偏移量没有找到对应元素
                        if ((t = (ForkJoinTask<?>) U.getObject(a, j)) == null)
                            // 如果该对象就是 top元素 (top元素对应的下标为 top-1) 就代表已经被移除了
                            return s + 1 == top;     // shorter than expected
                            // 判断下个元素 与传入元素是否匹配 看来这里只会执行 top or base 其他任务 即使匹配了 也因为 removed == false 而不执行
                        else if (t == task) {
                            boolean removed = false;
                            // 代表传入的 task 就是顶部的任务
                            if (s + 1 == top) {      // pop
                                // 尝试移除顶部元素
                                if (U.compareAndSwapObject(a, j, task, null)) {
                                    U.putOrderedInt(this, QTOP, s);
                                    removed = true;
                                }
                                // 如果是底部 先将 task 置空 保证偏移量不变
                            } else if (base == b)      // replace with proxy
                                removed = U.compareAndSwapObject(
                                        a, j, task, new EmptyTask());
                            // removed 代表CAS 成功 可以正常执行任务
                            if (removed)
                                // 执行任务后会退到外层  之后根据task.status 来判断是否完成任务 完成了就返回false
                                task.doExec();
                            break;
                            // 如果任务已经完成 且是 顶部元素
                        } else if (t.status < 0 && s + 1 == top) {
                            if (U.compareAndSwapObject(a, j, t, null))
                                U.putOrderedInt(this, QTOP, s);
                            break;                  // was cancelled
                        }
                        // 代表没有任务可取
                        if (--n == 0)
                            return false;
                    }
                    // 如果任务已经完成 那么 执行就是失败的
                    if (task.status < 0)
                        return false;
                }
            }
            return true;
        }

        /**
         * Pops task if in the same CC computation as the given task,
         * in either shared or owned mode. Used only by helpComplete.
         *
         * @param mode 代表是 独占模式还是共享模式
         */
        final CountedCompleter<?> popCC(CountedCompleter<?> task, int mode) {
            int s;
            ForkJoinTask<?>[] a;
            Object o;
            if (base - (s = top) < 0 && (a = array) != null) {
                // 这里只获取第一个任务 因为 CountedCompleter 是一个链表结构 依次向上遍历找到匹配的 task
                long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
                // 如果获取到的任务是 CountedCompleter
                if ((o = U.getObjectVolatile(a, j)) != null &&
                        (o instanceof CountedCompleter)) {
                    CountedCompleter<?> t = (CountedCompleter<?>) o;
                    for (CountedCompleter<?> r = t; ; ) {
                        if (r == task) {
                            // <0 看来是独占模式 这里需要加锁
                            if (mode < 0) { // must lock
                                if (U.compareAndSwapInt(this, QLOCK, 0, 1)) {
                                    // 代表是首个任务 弹出任务
                                    if (top == s && array == a &&
                                            U.compareAndSwapObject(a, j, t, null)) {
                                        U.putOrderedInt(this, QTOP, s - 1);
                                        U.putOrderedInt(this, QLOCK, 0);
                                        return t;
                                    }
                                    U.compareAndSwapInt(this, QLOCK, 1, 0);
                                }
                                // 如果是共享模式 这里就是不需要加锁
                            } else if (U.compareAndSwapObject(a, j, t, null)) {
                                U.putOrderedInt(this, QTOP, s - 1);
                                return t;
                            }
                            break;
                            // 没有找到匹配的任务时 获取 completer 的父任务
                        } else if ((r = r.completer) == null) // try parent
                            break;
                    }
                }
            }
            return null;
        }

        /**
         * Steals and runs a task in the same CC computation as the
         * given task if one exists and can be taken without
         * contention. Otherwise returns a checksum/control value for
         * use by method helpComplete.
         *
         * @return 1 if successful, 2 if retryable (lost to another
         * stealer), -1 if non-empty but no matching task found, else
         * the base index, forced negative.
         * 拉取并执行 CC 任务
         */
        final int pollAndExecCC(CountedCompleter<?> task) {
            int b, h;
            ForkJoinTask<?>[] a;
            Object o;
            // 代表 当前workQueue没有被初始化
            if ((b = base) - top >= 0 || (a = array) == null)
                h = b | Integer.MIN_VALUE;  // to sense movement on re-poll
            else {
                // 获取队列的底部元素
                long j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                // 如果底部元素不存在了 代表是被其他线程窃取了
                if ((o = U.getObjectVolatile(a, j)) == null)
                    h = 2;                  // retryable
                    // 如果底部元素不是 CC 类型代表匹配异常
                else if (!(o instanceof CountedCompleter))
                    h = -1;                 // unmatchable
                    // 代表底部元素是 CC类型
                else {
                    CountedCompleter<?> t = (CountedCompleter<?>) o;
                    for (CountedCompleter<?> r = t; ; ) {
                        if (r == task) {
                            // 代表底部元素匹配成功 执行任务并移除该任务
                            if (base == b &&
                                    U.compareAndSwapObject(a, j, t, null)) {
                                base = b + 1;
                                t.doExec();
                                h = 1;      // success
                                // CAS 失败 代表可重试
                            } else
                                h = 2;      // lost CAS
                            break;
                            // 代表没有找到匹配元素
                        } else if ((r = r.completer) == null) {
                            h = -1;         // unmatched
                            break;
                        }
                    }
                }
            }
            return h;
        }

        /**
         * Returns true if owned and not known to be blocked.
         * 判断当前是否处在阻塞状态
         */
        final boolean isApparentlyUnblocked() {
            Thread wt;
            Thread.State s;
            // scanState 代表当前处在忙碌状态
            return (scanState >= 0 &&
                    // 所属线程不为空
                    (wt = owner) != null &&
                    // 线程状态非阻塞 或者 WAITING
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING);
        }

        // Unsafe mechanics. Note that some are (and must be) the same as in FJP
        private static final sun.misc.Unsafe U;
        /**
         * 代表到数组的偏移量  配合 CAS.getObject  直接定位到某个元素
         */
        private static final int ABASE;
        /**
         * 代表数组中每个元素是 2的多少次幂
         */
        private static final int ASHIFT;
        /**
         * 对应top 字段 用于CAS 操作
         */
        private static final long QTOP;
        /**
         * 代表是否上锁的字段
         */
        private static final long QLOCK;
        private static final long QCURRENTSTEAL;

        static {
            try {
                U = sun.misc.Unsafe.getUnsafe();
                Class<?> wk = WorkQueue.class;
                Class<?> ak = ForkJoinTask[].class;
                QTOP = U.objectFieldOffset
                        (wk.getDeclaredField("top"));
                QLOCK = U.objectFieldOffset
                        (wk.getDeclaredField("qlock"));
                QCURRENTSTEAL = U.objectFieldOffset
                        (wk.getDeclaredField("currentSteal"));
                ABASE = U.arrayBaseOffset(ak);
                int scale = U.arrayIndexScale(ak);
                if ((scale & (scale - 1)) != 0)
                    throw new Error("data type scale not a power of two");
                ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    // static fields (initialized in static initializer below)

    /**
     * Creates a new ForkJoinWorkerThread. This factory is used unless
     * overridden in ForkJoinPool constructors.
     * 代表 forkjoin 的线程工厂
     */
    public static final ForkJoinWorkerThreadFactory
            defaultForkJoinWorkerThreadFactory;

    /**
     * Permission required for callers of methods that may start or
     * kill threads.
     * 请求  执行可能会启动或者杀死线程的方法 的权限对象
     */
    private static final RuntimePermission modifyThreadPermission;

    /**
     * Common (static) pool. Non-null for public use unless a static
     * construction exception, but internal usages null-check on use
     * to paranoically avoid potential initialization circularities
     * as well as to simplify generated code.
     * 公有的静态线程池
     */
    static final ForkJoinPool common;

    /**
     * Common pool parallelism. To allow simpler use and management
     * when common pool threads are disabled, we allow the underlying
     * common.parallelism field to be zero, but in that case still report
     * parallelism as 1 to reflect resulting caller-runs mechanics.
     * 公共线程池 允许的最大并发数
     */
    static final int commonParallelism;

    /**
     * Limit on spare thread construction in tryCompensate.
     * 备用线程数 在 tryCompensate 中使用
     */
    private static int commonMaxSpares;

    /**
     * Sequence number for creating workerNamePrefix.
     * 创建 worker 名称前缀的序号
     */
    private static int poolNumberSequence;

    /**
     * Returns the next sequence number. We don't expect this to
     * ever contend, so use simple builtin sync.
     * 通过内置锁 保证 数值同步增长
     */
    private static final synchronized int nextPoolId() {
        return ++poolNumberSequence;
    }

    // static configuration constants

    /**
     * Initial timeout value (in nanoseconds) for the thread
     * triggering quiescence to park waiting for new work. On timeout,
     * the thread will instead try to shrink the number of
     * workers. The value should be large enough to avoid overly
     * aggressive shrinkage during most transient stalls (long GCs
     * etc).
     * 线程阻塞 等待新任务的时间 (空闲等待时间)
     */
    private static final long IDLE_TIMEOUT = 2000L * 1000L * 1000L; // 2sec

    /**
     * Tolerance for idle timeouts, to cope with timer undershoots
     * 空闲超时时间  防止 timer 未命中
     */
    private static final long TIMEOUT_SLOP = 20L * 1000L * 1000L;  // 20ms

    /**
     * The initial value for commonMaxSpares during static
     * initialization. The value is far in excess of normal
     * requirements, but also far short of MAX_CAP and typical
     * OS thread limits, so allows JVMs to catch misuse/abuse
     * before running out of resources needed to do so.
     * 默认的备用线程数量
     */
    private static final int DEFAULT_COMMON_MAX_SPARES = 256;

    /**
     * Number of times to spin-wait before blocking. The spins (in
     * awaitRunStateLock and awaitWork) currently use randomized
     * spins. Currently set to zero to reduce CPU usage.
     * <p>
     * If greater than zero the value of SPINS must be a power
     * of two, at least 4.  A value of 2048 causes spinning for a
     * small fraction of typical context-switch times.
     * <p>
     * If/when MWAIT-like intrinsics becomes available, they
     * may allow quieter spinning.
     * 阻塞前自旋的次数
     */
    private static final int SPINS = 0;

    /**
     * Increment for seed generators. See class ThreadLocal for
     * explanation.
     * index_seed 的增量
     */
    private static final int SEED_INCREMENT = 0x9e3779b9;

    /**
     * 关于 线程池运行状态的一些位信息
     * ctl 64位 记录了 4组信息 每组以16位为范围
     * Bits and masks for field ctl, packed with 4 16 bit subfields:
     * AC 代表 活跃的工作者数量 - 目标并行度
     * AC: Number of active running workers minus target parallelism
     * TC 代表 总的工作者数量 - 目标并行度
     * TC: Number of total workers minus target parallelism
     * 代表栈顶的那个idleWorker 对象  这个16位的第一个记录该worker 是active 还是 inactive 后15位 代表版本信息
     * SS: version count and status of top waiting thread
     * 代表栈顶的 idleWorker 在wqs 中的下标 (索引)   可以通过 stackPred 获取到下一个 idleWorker
     * ID: poolIndex of top of Treiber stack of waiters
     * <p>
     * When convenient, we can extract the lower 32 stack top bits
     * (including version bits) as sp=(int)ctl.  The offsets of counts
     * by the target parallelism and the positionings of fields makes
     * it possible to perform the most common checks via sign tests of
     * fields: When ac is negative, there are not enough active
     * workers, when tc is negative, there are not enough total
     * workers.  When sp is non-zero, there are waiting workers.  To
     * deal with possibly negative fields, we use casts in and out of
     * "short" and/or signed shifts to maintain signedness.
     * <p>
     * Because it occupies uppermost bits, we can add one active count
     * using getAndAddLong of AC_UNIT, rather than CAS, when returning
     * from a blocked join.  Other updates entail multiple subfields
     * and masking, requiring CAS.
     */

    // Lower and upper word masks
    /**
     * 代表低32位掩码
     */
    private static final long SP_MASK = 0xffffffffL;
    /**
     * UC_MASK 代表高位掩码
     */
    private static final long UC_MASK = ~SP_MASK;

    // Active counts    活跃线程数
    private static final int AC_SHIFT = 48;
    /**
     * 代表有关活跃计数增加的最小单位
     */
    private static final long AC_UNIT = 0x0001L << AC_SHIFT;
    /**
     * 活跃线程数的掩码
     */
    private static final long AC_MASK = 0xffffL << AC_SHIFT;

    // Total counts    工作线程数
    private static final int TC_SHIFT = 32;
    /**
     * 工作线程数增加的最小单位
     */
    private static final long TC_UNIT = 0x0001L << TC_SHIFT;
    /**
     * 获取工作线程数的掩码
     */
    private static final long TC_MASK = 0xffffL << TC_SHIFT;
    /**
     * 创建工作线程的标志
     */
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); // sign

    // runState bits: SHUTDOWN must be negative, others arbitrary powers of
    /**
     * 第一位信息 记录当前线程池是否被锁定
     */
    private static final int RSLOCK = 1;
    /**
     * 第二位信息 记录是否需要被唤醒
     */
    private static final int RSIGNAL = 1 << 1;
    /**
     * 第3位信息记录了 当前线程池 是否处于运行状态
     */
    private static final int STARTED = 1 << 2;
    /**
     * 30位代表是否处于停止状态
     */
    private static final int STOP = 1 << 29;
    /**
     * 31位代表是否处于TERMINATED状态
     */
    private static final int TERMINATED = 1 << 30;
    /**
     * 32位代表是否处在 SHUTDOWN 状态
     */
    private static final int SHUTDOWN = 1 << 31;

    // Instance fields
    /**
     * 记录线程池的 控制信息 配合一些掩码可以获取到需要的值
     */
    volatile long ctl;                   // main pool control
    /**
     * 记录当前运行状态下
     */
    volatile int runState;               // lockable status
    /**
     * 标记线程池相关的配置 (并行模式)  并行度 最高只有 16位
     */
    final int config;                    // parallelism, mode
    /**
     * 用于生成工作者下标
     */
    int indexSeed;                       // to generate worker index
    /**
     * 任务队列 每个forkjoin线程 会单独维护一个工作数组 一般情况会从数组中拉取任务 如果没有任务拉取了就尝试从 其他workQueue 拉取任务
     * 外部线程创建的任务 会添加到 某个 workqueue 中
     */
    volatile WorkQueue[] workQueues;     // main registry
    /**
     * 线程工厂
     */
    final ForkJoinWorkerThreadFactory factory;
    /**
     * 针对未受检异常的处理器对象  属于全局范围 每个worker 对象在初始化时 会尝试设置该对象
     */
    final UncaughtExceptionHandler ueh;  // per-worker UEH
    /**
     * 创建的 worker name前缀
     */
    final String workerNamePrefix;       // to create worker name string
    /**
     * 偷取任务的数量  也作为同步监控器
     */
    volatile AtomicLong stealCounter;    // also used as sync monitor

    /**
     * Acquires the runState lock; returns current (locked) runState.
     * 对当前运行状态进行上锁 并返回当前状态
     */
    private int lockRunState() {
        int rs;
        // 代表 第一位 数字记录了有关锁的信息 1 代表上锁 0 代表无锁
        return ((((rs = runState) & RSLOCK) != 0 ||
                // 如果未上锁的情况 申请上锁
                !U.compareAndSwapInt(this, RUNSTATE, rs, rs |= RSLOCK)) ?
                // 上锁成功返回 rs  上锁失败 阻塞并等待上锁
                awaitRunStateLock() : rs);
    }

    /**
     * Spins and/or blocks until runstate lock is available.  See
     * above for explanation.
     * 自旋或阻塞 直到能为 runstate 进行上锁
     */
    private int awaitRunStateLock() {
        Object lock;
        // 默认没有被打断
        boolean wasInterrupted = false;
        // SPINS 使用的是 静态变量 也就是每个对象共享
        for (int spins = SPINS, r = 0, rs, ns; ; ) {
            // 代表还没有上锁
            if (((rs = runState) & RSLOCK) == 0) {
                // 代表上锁成功
                if (U.compareAndSwapInt(this, RUNSTATE, rs, ns = rs | RSLOCK)) {
                    // 如果中途被打断过 触发线程打断方法
                    if (wasInterrupted) {
                        try {
                            Thread.currentThread().interrupt();
                        } catch (SecurityException ignore) {
                        }
                    }
                    return ns;
                }
            }
            // 已经上锁成功的情况下 且 r 为0  （一开始r就是0）  更新seed 信息
            else if (r == 0)
                r = ThreadLocalRandom.nextSecondarySeed();
                // 如果已经上锁成功 且 r 不为0  且 spins 大于0
            else if (spins > 0) {
                // ^  2数 对应的位上不同 算作 1  相同算作0
                r ^= r << 6;
                r ^= r >>> 21;
                r ^= r << 7; // xorshift
                // 每次进入这里会将 spins 做一系列的转换
                if (r >= 0)
                    --spins;
            }
            // 如果当前线程池没有在运行状态 或者 偷取数量为 null 这里应该是等待某个地方进行初始化
            else if ((rs & STARTED) == 0 || (lock = stealCounter) == null)
                Thread.yield();   // initialization race
            else if (U.compareAndSwapInt(this, RUNSTATE, rs, rs | RSIGNAL)) {
                synchronized (lock) {
                    if ((runState & RSIGNAL) != 0) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ie) {
                            if (!(Thread.currentThread() instanceof
                                    ForkJoinWorkerThread))
                                wasInterrupted = true;
                        }
                    } else
                        lock.notifyAll();
                }
            }
        }
    }

    /**
     * Unlocks and sets runState to newRunState.
     *
     * @param oldRunState a value returned from lockRunState
     * @param newRunState the next value (must have lock bit clear).
     */
    private void unlockRunState(int oldRunState, int newRunState) {
        if (!U.compareAndSwapInt(this, RUNSTATE, oldRunState, newRunState)) {
            Object lock = stealCounter;
            runState = newRunState;              // clears RSIGNAL bit
            if (lock != null)
                synchronized (lock) {
                    lock.notifyAll();
                }
        }
    }

    // Creating, registering and deregistering workers

    /**
     * Tries to construct and start one worker. Assumes that total
     * count has already been incremented as a reservation.  Invokes
     * deregisterWorker on any failure.
     *
     * @return true if successful
     * 创建工作者对象
     */
    private boolean createWorker() {
        // 工作者线程工厂
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
            // 通过线程工厂初始化 ForkJoinThread 对象
            // 每个 worker 都会对应一个 wq 对象 而有些wq 可能没有对应的worker 对象
            if (fac != null && (wt = fac.newThread(this)) != null) {
                wt.start();
                return true;
            }
        } catch (Throwable rex) {
            ex = rex;
        }
        // 异常情况注销worker
        deregisterWorker(wt, ex);
        return false;
    }

    /**
     * Tries to add one worker, incrementing ctl counts before doing
     * so, relying on createWorker to back out on failure.
     *
     * @param c incoming ctl value, with total count negative and no
     *          idle workers.  On CAS failure, c is refreshed and retried if
     *          this holds (otherwise, a new worker is not needed).
     *          先增加ctl 的计数 之后创建新的 worker 如果失败就恢复计数
     */
    private void tryAddWorker(long c) {
        boolean add = false;
        do {
            // 只获取 AC 和 TC 的信息
            // AC 代表活跃的工作者数量  TC 代表总的工作者数量
            long nc = ((AC_MASK & (c + AC_UNIT)) |
                    (TC_MASK & (c + TC_UNIT)));
            // 如果传入的 c 就是 ctl 一般情况就是符合该条件
            if (ctl == c) {
                int rs, stop;                 // check if terminating
                // 代表当前不是 STOP 状态 那么就更新 CTL 信息 也就是增加一个 工作者 和一个活跃工作者
                if ((stop = (rs = lockRunState()) & STOP) == 0)
                    add = U.compareAndSwapLong(this, CTL, c, nc);
                // 尝试进行解锁
                unlockRunState(rs, rs & ~RSLOCK);
                // 如果当前已经处在 STOP 的状态直接返回
                if (stop != 0)
                    break;
                if (add) {
                    // 创建 工作者对象
                    createWorker();
                    break;
                }
            }
            // 这里要求 c == 0 才会进入下次循环 一般调用的时候c != 0
        } while (((c = ctl) & ADD_WORKER) != 0L && (int) c == 0);
    }

    /**
     * Callback from ForkJoinWorkerThread constructor to establish and
     * record its WorkQueue.
     *
     * @param wt the worker thread
     * @return the worker's queue
     * 将一条工作线程注册到线程池中 并返回该线程对应的 工作队列  工作线程不是只处理匹配的 wq 对象 因为有些wq 对象是没有对应的 owner 的
     */
    final WorkQueue registerWorker(ForkJoinWorkerThread wt) {
        // 当线程发生了某种未受检异常时 会处理异常
        UncaughtExceptionHandler handler;
        // 将forkjoin 线程设置成 守护线程
        wt.setDaemon(true);                           // configure thread
        // 相当于用了一个副本对象
        if ((handler = ueh) != null)
            wt.setUncaughtExceptionHandler(handler);
        // 初始化forkjoin 任务队列   如果通过添加外部任务来唤醒整个线程池对象那么 一开始会先创建一个 没有owner 的wq 对象 （因为owner 必须是 FJThread） 之后
        // 为了创建能执行任务的worker 又需要新建一个 FJThread  该 线程又会创建一个 wq 对象 这样就会有2个 wq 对象
        WorkQueue w = new WorkQueue(this, wt);
        int i = 0;                                    // assign a pool index
        // 获取模式配置
        int mode = config & MODE_MASK;
        // 为当前状态上锁
        int rs = lockRunState();
        try {
            WorkQueue[] ws;
            int n;                    // skip if no array
            // 获取当前 队列信息
            if ((ws = workQueues) != null && (n = ws.length) > 0) {

                // start  这段就是生成一个index值
                // 可能发生碰撞    SEED_INCREMENT 代表每次seed 增加的 单位长度
                int s = indexSeed += SEED_INCREMENT;  // unlikely to collide
                int m = n - 1;
                // 通过 尾部设置为1 保证该值是奇数
                i = ((s << 1) | 1) & m;               // odd-numbered indices
                // end

                // 代表发生碰撞 需要更换下标
                if (ws[i] != null) {                  // collision
                    int probes = 0;                   // step by approx half n
                    // 判断数组长度是否 <= 4  是的话 step=2 否则按照公式计算
                    int step = (n <= 4) ? 2 : ((n >>> 1) & EVENMASK) + 2;
                    // 更新下标后 如果继续发生碰撞  该方法会直到 找到一个 空的 slot
                    while (ws[i = (i + step) & m] != null) {
                        // 每次 新的碰撞就增加 探针偏移量 当超过了 n 也就是碰撞次数 达到数组长度时  也就是相当于遍历了一轮
                        if (++probes >= n) {
                            // 进行扩容
                            workQueues = ws = Arrays.copyOf(ws, n <<= 1);
                            // 更新m的值
                            m = n - 1;
                            // 重置探针
                            probes = 0;
                        }
                    }
                }

                // hint 代表使用的seed  可以理解为一个随机值
                w.hint = s;                           // use as random seed
                // config 中携带了 数组下标 以及 mode 信息  这个mode 信息是从 pool中取出来的
                w.config = i | mode;
                // scanState 就是 下标的值
                w.scanState = i;                      // publication fence
                // 将 workThread 设置到数组中
                ws[i] = w;
            }
        } finally {
            // 解锁
            unlockRunState(rs, rs & ~RSLOCK);
        }
        // 为线程设置特殊的名称
        wt.setName(workerNamePrefix.concat(Integer.toString(i >>> 1)));
        return w;
    }

    /**
     * Final callback from terminating worker, as well as upon failure
     * to construct or start a worker.  Removes record of worker from
     * array, and adjusts counts. If pool is shutting down, tries to
     * complete termination.
     *
     * @param wt the worker thread, or null if construction failed
     * @param ex the exception causing failure, or null if none
     *           通过传入的线程对象和 抛出的异常注销该 worker
     */
    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        WorkQueue w = null;
        // 确保数据有效性
        if (wt != null && (w = wt.workQueue) != null) {
            WorkQueue[] ws;                           // remove index from array
            // SMASK 应该是 获取下标的 掩码  每个w 对象本身有自己在 wqs中的 下标信息
            int idx = w.config & SMASK;
            // 尝试锁定当前状态
            int rs = lockRunState();
            // 确保 该wq 对象确实存在后将元素置空
            if ((ws = workQueues) != null && ws.length > idx && ws[idx] == w)
                ws[idx] = null;
            // 解除锁定状态
            unlockRunState(rs, rs & ~RSLOCK);
        }
        long c;                                       // decrement counts
        do {
        } while (!U.compareAndSwapLong
                // 减少活跃工作者 总工作者 并将结果进行拼接
                        (this, CTL, c = ctl, ((AC_MASK & (c - AC_UNIT)) |
                                (TC_MASK & (c - TC_UNIT)) |
                                (SP_MASK & c))));
        // 将当前 worker信息置空
        if (w != null) {
            w.qlock = -1;                             // ensure set
            // 将worker 的偷取任务数量 转移到 pool 中
            w.transferStealCount(this);
            // 关闭所有未执行的任务  就是将 task.status设置成完成
            w.cancelAll();                            // cancel remaining tasks
        }
        for (; ; ) {                                    // possibly replace
            WorkQueue[] ws;
            int m, sp;
            // 尝试终止线程池  或者 worker 某些属性无效后 退出循环
            if (tryTerminate(false, false) || w == null || w.array == null ||
                    (runState & STOP) != 0 || (ws = workQueues) == null ||
                    (m = ws.length - 1) < 0)              // already terminating
                break;
            // 当 ctl 信息有效时  看来 ctl & m 能直接得到一个 下标值 该值是栈顶的 wq 对象吗
            if ((sp = (int) (c = ctl)) != 0) {         // wake up replacement
                if (tryRelease(c, ws[sp & m], AC_UNIT))
                    break;
            // 当存在异常信息 且 需要添加工作者时
            } else if (ex != null && (c & ADD_WORKER) != 0L) {
                // 创建一个替代品
                tryAddWorker(c);                      // create replacement
                break;
            } else                                      // don't need replacement
                break;
        }
        // 清除过期的 异常信息
        if (ex == null)                               // help clean on way out
            ForkJoinTask.helpExpungeStaleExceptions();
        // 抛出异常
        else                                          // rethrow
            ForkJoinTask.rethrow(ex);
    }

    // Signalling

    /**
     * Tries to create or activate a worker if too few are active.
     *
     * @param ws the worker array to use to find signallees  整个 工作队列数组
     * @param q  a WorkQueue --if non-null, don't retry if now empty  被指定添加任务的工作队列
     *           尝试新建或者唤醒一个线程  注意 只有在 发现某个队列的任务只剩一个的时候才会尝试调用
     *           可能 之前空闲线程都 沉睡了 然后 这时 新添了一个任务都某个队列中且该队列只有一个任务 那么就需要激活额外的线程(唤醒空闲的或者新建)
     *           来执行任务 如果队列中本身就有任务意味着 该队列的任务正在被某个线程执行
     */
    final void signalWork(WorkQueue[] ws, WorkQueue q) {
        long c;
        int sp, i;
        WorkQueue v;
        Thread p;
        // 当 ctl 小于0 时代表活跃的worker 太少 需要不断自旋  小于0 是什么意思???
        while ((c = ctl) < 0L) {                       // too few active
            // 如果 ctl == 0 代表没有空闲的工作者 这里新增worker 后就直接返回了
            if ((sp = (int) c) == 0) {                  // no idle workers
                // 如果当前状态 有 ADD_WORKER的信息 代表需要增加 worker  这个标识是由谁在什么时机设置的???
                if ((c & ADD_WORKER) != 0L)            // too few workers
                    // 增加工作者
                    tryAddWorker(c);
                break;
            }
            // 如果 ws==null 可能是 pool 本身还没初始化 或者 线程池已经终止
            if (ws == null)                            // unstarted/terminated
                break;
            // sp & SMASK 是什么???  推测 这里获取的是 创建 ws 时的长度   如果长度变小了 就代表可能处在终止状态
            if (ws.length <= (i = sp & SMASK))         // terminated
                break;
            // 如果对应的slot 没有数据 代表正在终止中
            if ((v = ws[i]) == null)                   // terminating
                break;
            // ctl + 版本时间戳 避免 ABA 问题  且设置成 非失活状态
            int vs = (sp + SS_SEQ) & ~INACTIVE;        // next scanState
            // 将sp 减去最后一个 scan状态信息  这里是在做什么
            int d = sp - v.scanState;                  // screen CAS
            // 为 ctl 增加一个 活跃线程数   将前一个栈顶信息拼接上去
            long nc = (UC_MASK & (c + AC_UNIT)) | (SP_MASK & v.stackPred);
            // 更新 ctl 信息
            if (d == 0 && U.compareAndSwapLong(this, CTL, c, nc)) {
                // 代表v 当前处在活跃状态
                v.scanState = vs;                      // activate v
                // 如果v 当前有暂停的线程 就进行唤醒
                if ((p = v.parker) != null)
                    U.unpark(p);
                break;
            }
            // 代表没有任务了 就不需要再 唤醒线程了
            if (q != null && q.base == q.top)          // no more work
                break;
        }
    }

    /**
     * Signals and releases worker v if it is top of idle worker
     * stack.  This performs a one-shot version of signalWork only if
     * there is (apparently) at least one idle worker.
     *
     * @param c   incoming ctl value  ctl 的值
     * @param v   if non-null, a worker  worker 可以为空
     * @param inc the increment to active count (zero when compensating)  代表增量值
     * @return true if successful
     * 恢复某个失活的线程(wq)
     */
    private boolean tryRelease(long c, WorkQueue v, long inc) {
        // c 代表当前 ctl  vs 是 增加版本号后 设置成活跃状态
        int sp = (int) c, vs = (sp + SS_SEQ) & ~INACTIVE;
        Thread p;
        // 如果 ctl 低32位等于 scanState 代表可以唤醒 线程
        if (v != null && v.scanState == sp) {          // v is at top of stack
            // 获取 高32位数 拼接上 失活前 ctl 的低32位数
            long nc = (UC_MASK & (c + inc)) | (SP_MASK & v.stackPred);
            // 更新成活跃状态
            if (U.compareAndSwapLong(this, CTL, c, nc)) {
                // 修改scanState 的值
                v.scanState = vs;
                // 如果有暂停的线程就唤醒它
                if ((p = v.parker) != null)
                    U.unpark(p);
                return true;
            }
        }
        return false;
    }

    // Scanning for tasks

    /**
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     * 执行工作队列中的任务  此时 workQueue.array 还没有被初始化
     */
    final void runWorker(WorkQueue w) {
        // 初始化 array
        w.growArray();                   // allocate queue
        // 获取 seed 信息 该值可以理解为一个随机值
        int seed = w.hint;               // initially holds randomization hint
        int r = (seed == 0) ? 1 : seed;  // avoid 0 for xorShift
        for (ForkJoinTask<?> t; ; ) {
            // 也就是这里会不断扫描队列中的任务并执行 一旦没有任务了 就阻塞等待新任务的添加

            // 尝试扫描任务 如果不为空 执行该任务  有可能偷到的队列就是传入的队列吗???
            if ((t = scan(w, r)) != null)
                // 这个w 是当前执行任务的队列 而t 则是从其他队列中偷来的任务
                // 这里使用该 wq 执行偷来的任务 同时执行 对列中剩余的任务
                w.runTask(t);
                // 如果没有扫描到任务 就阻塞
            else if (!awaitWork(w, r))
                break;
            // 使用位运算更新 r 后重新扫描
            r ^= r << 13;
            r ^= r >>> 17;
            r ^= r << 5; // xorshift
        }
    }

    /**
     * Scans for and tries to steal a top-level task. Scans start at a
     * random location, randomly moving on apparent contention,
     * otherwise continuing linearly until reaching two consecutive
     * empty passes over all queues with the same checksum (summing
     * each base index of each queue, that moves on each steal), at
     * which point the worker tries to inactivate and then re-scans,
     * attempting to re-activate (itself or some other worker) if
     * finding a task; otherwise returning null to await work.  Scans
     * otherwise touch as little memory as possible, to reduce
     * disruption on other scanning threads.
     *
     * @param w the worker (via its WorkQueue)  工作者队列
     * @param r a random seed  随机因子
     * @return a task, or null if none found
     * 通过随机因子 在工作者队列中寻找某个任务对象
     */
    private ForkJoinTask<?> scan(WorkQueue w, int r) {
        WorkQueue[] ws;
        int m;
        // 首先确保数据的正确性
        if ((ws = workQueues) != null && (m = ws.length - 1) > 0 && w != null) {
            // 当前的扫描状态  一开始 scanState 就是 该wq 在 wqs 中的index
            int ss = w.scanState;                     // initially non-negative
            // origin 是 将 seed 与 ws.length 处理后得到的 相当于一个随机的 下标
            for (int origin = r & m, k = origin, oldSum = 0, checkSum = 0; ; ) {
                WorkQueue q;
                ForkJoinTask<?>[] a;
                ForkJoinTask<?> t;
                int b, n;
                long c;
                // 获取一个随机的 任务队列
                if ((q = ws[k]) != null) {
                    // 代表该随机获取的 队列中存在要处理的任务 每个线程不是直接处理对应的wq 吗 这样不会发生竞争吗
                    if ((n = (b = q.base) - q.top) < 0 &&
                            (a = q.array) != null) {      // non-empty
                        // 找到底部元素的偏移量  看来从其他队列拉取就是拉取底部任务  因为拉取 顶部更容易发生竞争
                        long i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                        // 获取 base 对象
                        if ((t = ((ForkJoinTask<?>)
                                U.getObjectVolatile(a, i))) != null &&
                                q.base == b) {
                            // 代表还没有失活 这样直接获取任务base 就好
                            if (ss >= 0) {
                                // 将 base任务置空
                                if (U.compareAndSwapObject(a, i, t, null)) {
                                    // 代表底部任务被消费
                                    q.base = b + 1;
                                    // 代表还有其他待唤醒任务
                                    if (n < -1)       // signal others
                                        // 唤醒工作者去处理其他任务  这里只是唤醒应该不会阻塞当前执行t任务的线程
                                        signalWork(ws, q);
                                    // 这里将找到的任务返回
                                    return t;
                                }
                                // 如果已经失活 就要尝试恢复线程
                            } else if (oldSum == 0 &&   // try to activate
                                    w.scanState < 0)
                                // 此时 c 是失活前的 ctl ctl & m 好像是获取到 栈顶的wq对象
                                tryRelease(c = ctl, ws[m & (int) c], AC_UNIT);
                        }
                        // 更新 ss 的值
                        if (ss < 0)                   // refresh
                            ss = w.scanState;
                        // 更新随机因子后
                        r ^= r << 1;
                        r ^= r >>> 3;
                        r ^= r << 10;
                        // 与 wqs 取余 后 便于获取下一个 wq 对象 再拉取待处理任务
                        origin = k = r & m;           // move and rescan
                        oldSum = checkSum = 0;
                        continue;
                    }
                    // 增加 base 的值
                    checkSum += b;
                }
                // 原本 每次 k+1 代表获取下一个元素 如果取余与origin相等 代表已经轮了一圈
                // 正常情况 只要 slot 中有对应的 wq 对象就会更新 随机因子 只有当 slot为空时 才会挨个去尝试获取wq
                if ((k = (k + 1) & m) == origin) {    // continue until stable
                    if ((ss >= 0 || (ss == (ss = w.scanState))) &&
                            oldSum == (oldSum = checkSum)) {
                        // 如果发现已经失活 代表已经走了2轮  第一轮都没有找到就设置成失活 第二轮还是没有 就会退出自旋
                        if (ss < 0 || w.qlock < 0)    // already inactive
                            break;
                        // 设置成失活
                        int ns = ss | INACTIVE;       // try to inactivate
                        // 减少活跃线程
                        long nc = ((SP_MASK & ns) |
                                (UC_MASK & ((c = ctl) - AC_UNIT)));
                        // stackPred 代表 该线程失活前 记录的 ctl 状态
                        w.stackPred = (int) c;         // hold prev stack top
                        // 扫描状态变成 ns
                        U.putInt(w, QSCANSTATE, ns);
                        // 更新  ctl
                        if (U.compareAndSwapLong(this, CTL, c, nc))
                            ss = ns;
                        else
                            w.scanState = ss;         // back out
                    }
                    // 注意走了一圈后会重置 checkSum  该值是做什么的???
                    checkSum = 0;
                }
            }
        }
        return null;
    }

    /**
     * Possibly blocks worker w waiting for a task to steal, or
     * returns false if the worker should terminate.  If inactivating
     * w has caused the pool to become quiescent, checks for pool
     * termination, and, so long as this is not the only worker, waits
     * for up to a given duration.  On timeout, if ctl has not
     * changed, terminates the worker, which will in turn wake up
     * another worker to possibly repeat this process.
     *
     * @param w the calling worker
     * @param r a random seed (for spins)   代表一个随机数
     * @return false if the worker should terminate
     */
    private boolean awaitWork(WorkQueue w, int r) {
        // 代表当前队列 已经终止 返回false
        if (w == null || w.qlock < 0)                 // w is terminating
            return false;
        // pred 是失活前的ctl
        for (int pred = w.stackPred, spins = SPINS, ss; ; ) {
            // scanState 非负数 就代表正在扫描 就跳出循环
            if ((ss = w.scanState) >= 0)
                break;
                // 如果自旋次数大于0
            else if (spins > 0) {
                // 重新生成随机值
                r ^= r << 6;
                r ^= r >>> 21;
                r ^= r << 7;
                // 代表自旋次数用完了
                if (r >= 0 && --spins == 0) {         // randomize spins
                    WorkQueue v;
                    WorkQueue[] ws;
                    int s, j;
                    AtomicLong sc;
                    if (pred != 0 && (ws = workQueues) != null &&
                            (j = pred & SMASK) < ws.length &&
                            (v = ws[j]) != null &&        // see if pred parking
                            (v.parker == null || v.scanState >= 0))
                        // 这里 spins 已经变成0 了 有重新设置保证能继续自旋
                        spins = SPINS;                // continue spinning
                }
                // 小于0 代表w 已经终止 所以返回false  不考虑阻塞
            } else if (w.qlock < 0)                     // recheck after spins
                return false;
                // 没有被打断
            else if (!Thread.interrupted()) {
                long c, prevctl, parkTime, deadline;
                // 获取当前活跃工作者数量
                int ac = (int) ((c = ctl) >> AC_SHIFT) + (config & SMASK);
                // 如果当前没有活跃线程数 且 尝试终止成功 或者 当前已经 是 STOP 就返回false
                if ((ac <= 0 && tryTerminate(false, false)) ||
                        (runState & STOP) != 0)           // pool terminating
                    return false;
                // 如果当前没有活跃线程数  w.scanState 和 ctl 是什么关系 很多地方都 有 ss == c 的判断
                if (ac <= 0 && ss == (int) c) {        // is last waiter
                    // 获取该线程失活前的 ctl 并在 增加AC 数量后 拼接
                    prevctl = (UC_MASK & (c + AC_UNIT)) | (SP_MASK & pred);
                    // 获取总的线程数
                    int t = (short) (c >>> TC_SHIFT);  // shrink excess spares
                    // 这里必须要求 只剩下 一个工作线程才能阻塞 否则不会阻塞
                    if (t > 2 && U.compareAndSwapLong(this, CTL, c, prevctl))
                        return false;                 // else use timed wait
                    // 获取最大等待时间
                    parkTime = IDLE_TIMEOUT * ((t >= 0) ? 1 : 1 - t);
                    deadline = System.nanoTime() + parkTime - TIMEOUT_SLOP;
                } else
                    // 有活跃线程数的情况 将 等待时间置为0
                    prevctl = parkTime = deadline = 0L;
                Thread wt = Thread.currentThread();
                // 设置 parkLocker对象
                U.putObject(wt, PARKBLOCKER, this);   // emulate LockSupport
                // 一般该 wt 就是 wq 对应的 FJThread
                w.parker = wt;
                // scanState < 0 代表失活
                if (w.scanState < 0 && ctl == c)      // recheck before park
                    // 暂停指定的时间
                    U.park(false, parkTime);
                // 唤醒后将 park 相关参数置空
                U.putOrderedObject(w, QPARKER, null);
                U.putObject(wt, PARKBLOCKER, null);
                // 代表正在扫描中 退出循环 否则下次自旋又会进入阻塞
                if (w.scanState >= 0)
                    break;
                // 代表虽然本次阻塞结束 但是还是失活状态
                if (parkTime != 0L && ctl == c &&
                        deadline - System.nanoTime() <= 0L &&
                        // CAS 更改状态后 返回false
                        U.compareAndSwapLong(this, CTL, c, prevctl))
                    return false;                     // shrink pool
            }
        }
        return true;
    }

    // Joining tasks

    /**
     * Tries to steal and run tasks within the target's computation.
     * Uses a variant of the top-level algorithm, restricted to tasks
     * with the given task as ancestor: It prefers taking and running
     * eligible tasks popped from the worker's own queue (via
     * popCC). Otherwise it scans others, randomly moving on
     * contention or execution, deciding to give up based on a
     * checksum (via return codes frob pollAndExecCC). The maxTasks
     * argument supports external usages; internal calls use zero,
     * allowing unbounded steps (external calls trap non-positive
     * values).
     *
     * @param w        caller  调用该方法的队列  该队列是从 wq[] 中按照某种散列函数获取的
     * @param maxTasks if non-zero, the maximum number of other tasks to run  代表执行的其他任务数量  如果为0 就是不做限制 不断的拉取CC任务
     * @return task status on exit
     */
    final int helpComplete(WorkQueue w, CountedCompleter<?> task,
                           int maxTasks) {
        WorkQueue[] ws;
        int s = 0, m;
        // 确保数据有效性
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 &&
                task != null && w != null) {
            int mode = w.config;                 // for popCC
            // 这2步好像是在计算下标
            int r = w.hint ^ w.top;              // arbitrary seed for origin
            int origin = r & m;                  // first queue to scan
            int h = 1;                           // 1:ran, >1:contended, <0:hash
            for (int k = origin, oldSum = 0, checkSum = 0; ; ) {
                CountedCompleter<?> p;
                WorkQueue q;
                // 如果任务已完成 退出循环
                if ((s = task.status) < 0)
                    break;
                // 从任务队列中弹出 CC   mode 代表是 共享模式还是独占模式  (CC 要求是workQueue 中top所对应的元素 且pop 会将CC 从 队列中移除)
                // 如果上次执行结果成功这里会继续拉取任务
                if (h == 1 && (p = w.popCC(task, mode)) != null) {
                    // 执行 CC 任务
                    p.doExec();                  // run local task
                    // 如果maxTask 刚好为0 返回
                    if (maxTasks != 0 && --maxTasks == 0)
                        break;
                    // 这2个是干嘛用的???
                    origin = k;                  // reset
                    oldSum = checkSum = 0;
                    // 进入下面这个分支代表 没有找到CC任务
                } else {                           // poll other queues
                    // 尝试去其他队列拉取任务 如果队列还没有初始化 设置成0  0是什么含义???
                    if ((q = ws[k]) == null)
                        h = 0;
                        // 这里代表找到了 wq 开始从底部尝试拉取任务 <0 代表没有找到对应的任务
                    else if ((h = q.pollAndExecCC(task)) < 0)
                        // 记录结果
                        checkSum += h;
                    // 2 代表需要重试  1 代表成功
                    if (h > 0) {
                        // 成功且不需要拉取其他任务就返回
                        if (h == 1 && maxTasks != 0 && --maxTasks == 0)
                            break;
                        // 更新hash因子后
                        r ^= r << 13;
                        r ^= r >>> 17;
                        r ^= r << 5; // xorshift
                        // 更新下标 重置状态  可以这样理解 如果1 代表成功 且maxTask == 0 也就是不再需要处理其他任务了 那么 不需要更新下标和重置 就可以返回
                        // 否则 无论 1还是2 都要更新下标 拉取新的任务
                        origin = k = r & m;      // move and restart
                        oldSum = checkSum = 0;
                        // 这里代表没有找到匹配任务 h = -1 或者 没有找到 对应的wq h=0
                        // TODO 这里还没有看懂
                    } else if ((k = (k + 1) & m) == origin) {
                        if (oldSum == (oldSum = checkSum))
                            break;
                        checkSum = 0;
                    }
                }
            }
        }
        return s;
    }

    /**
     * Tries to locate and execute tasks for a stealer of the given
     * task, or in turn one of its stealers, Traces currentSteal ->
     * currentJoin links looking for a thread working on a descendant
     * of the given task and with a non-empty queue to steal back and
     * execute tasks from. The first call to this method upon a
     * waiting join will often entail scanning/search, (which is OK
     * because the joiner has nothing better to do), but this method
     * leaves hints in workers to speed up subsequent calls.
     *
     * @param w    caller
     * @param task the task to join
     *             帮助窃取任务者执行它的任务???
     */
    private void helpStealer(WorkQueue w, ForkJoinTask<?> task) {
        // 获取工作队列对象
        WorkQueue[] ws = workQueues;
        int oldSum = 0, checkSum, m;
        // 确保数据正常
        if (ws != null && (m = ws.length - 1) >= 0 && w != null &&
                task != null) {
            do {                                       // restart point
                checkSum = 0;                          // for stability check
                ForkJoinTask<?> subtask;
                WorkQueue j = w, v;                    // v is subtask stealer
                descent:
                // 一开始 子任务 等同于传入的任务  确保任务还未完成
                for (subtask = task; subtask.status >= 0; ) {
                    // 因为 FJThread 都是放在奇数slot 所以每次都是进2位
                    for (int h = j.hint | 1, k = 0, i; ; k += 2) {
                        // 代表从头开始 自旋到尾部也没有找到偷取者
                        if (k > m)                     // can't find stealer
                            // 退出外层循环
                            break descent;
                        // 通过hash因子计算获取了一个 v 对象
                        if ((v = ws[i = (h + k) & m]) != null) {
                            // 如果当前wq 对象偷取的任务就是 本次传入的任务代表它就是偷取者
                            if (v.currentSteal == subtask) {
                                // 更新hint 值 便于下次快速定位
                                j.hint = i;
                                // 退出内层循环 进入下面的逻辑
                                break;
                            }
                            // 如果当前窃取的任务不是传入的任务 checkSum 就是 这些workQueue 的 最下层任务的下标
                            checkSum += v.base;
                        }
                    }
                    // 进入到这里代表 找到了偷取者
                    for (; ; ) {                         // help v or descend
                        ForkJoinTask<?>[] a;
                        int b;
                        // 找到偷取者的情况 还是增加了 checkSum 的值
                        checkSum += (b = v.base);
                        // 获取 偷取者当前等待的任务
                        ForkJoinTask<?> next = v.currentJoin;
                        // 如果目标任务已经完成了 或者 当前wq 等待的任务不是 传入的任务 或者偷取者偷取的任务不是当前任务  退出外层循环
                        if (subtask.status < 0 || j.currentJoin != subtask ||
                                v.currentSteal != subtask) // stale
                            break descent;
                        // 代表没有任务可以获取
                        if (b - v.top >= 0 || (a = v.array) == null) {
                            // 如果 偷取者的 join 任务为null 退出到外层也就是不考虑 帮助偷取者执行任务
                            if ((subtask = next) == null)
                                break descent;
                            // 将j 设置为v 查询 偷取者的偷取者
                            j = v;
                            break;
                        }
                        // 获取 底部元素
                        int i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                        ForkJoinTask<?> t = ((ForkJoinTask<?>)
                                U.getObjectVolatile(a, i));
                        // 这里偷取任务 只能从base 偷取
                        if (v.base == b) {
                            // 尾部为null 退出循环
                            if (t == null)             // stale
                                break descent;
                            // 将 底部元素置空
                            if (U.compareAndSwapObject(a, i, t, null)) {
                                // 更新底部元素偏移量
                                v.base = b + 1;
                                // 获取 该队列之前偷取的任务
                                ForkJoinTask<?> ps = w.currentSteal;
                                // 获取顶部下标
                                int top = w.top;
                                do {
                                    // 更新当前偷取的任务 并执行
                                    U.putOrderedObject(w, QCURRENTSTEAL, t);
                                    t.doExec();        // clear local tasks too
                                    // 完成偷取的任务后 如果目标任务还没有完成 且 顶部增加了新的任务 从队列中弹出任务并执行  这里是弹出 top任务
                                } while (task.status >= 0 &&
                                        w.top != top &&
                                        (t = w.pop()) != null);
                                // 还原偷取任务
                                U.putOrderedObject(w, QCURRENTSTEAL, ps);
                                // 当上面 w.top == top 时 会返回 也就是将 后面新增 的任务完成后在这里会退出
                                if (w.base != w.top)
                                    return;            // can't further help
                            }
                        }
                    }
                }
                // 目标任务没有完成的情况下 不断 自旋 帮助其他线程执行任务
            } while (task.status >= 0 && oldSum != (oldSum = checkSum));
        }
    }

    /**
     * Tries to decrement active count (sometimes implicitly) and
     * possibly release or create a compensating worker in preparation
     * for blocking. Returns false (retryable by caller), on
     * contention, detected staleness, instability, or termination.
     *
     * @param w caller
     *          判断是否允许 补偿线程 （返回ture 后线程会阻塞一段时间）
     */
    private boolean tryCompensate(WorkQueue w) {
        // 能否被阻塞
        boolean canBlock;
        WorkQueue[] ws;
        long c;
        int m, pc, sp;
        // 代表一些未初始化的情况 不满足阻塞条件
        if (w == null || w.qlock < 0 ||           // caller terminating
                (ws = workQueues) == null || (m = ws.length - 1) <= 0 ||
                (pc = config & SMASK) == 0)           // parallelism disabled
            canBlock = false;
        else if ((sp = (int) (c = ctl)) != 0)      // release idle worker
            // 尝试唤醒等待的线程 如果唤醒成功就能阻塞该线程 目的就是不减少当前可工作的线程
            // 又是 使用 ctl & m 的方式获取下标
            canBlock = tryRelease(c, ws[sp & m], 0L);
        else {
            // 获取活跃线程数
            int ac = (int) (c >> AC_SHIFT) + pc;
            // 总线程数
            int tc = (short) (c >> TC_SHIFT) + pc;
            int nbusy = 0;                        // validate saturation
            // 遍历ws 中每个元素
            for (int i = 0; i <= m; ++i) {        // two passes of odd indices
                WorkQueue v;
                // 获取奇数位的 元素
                if ((v = ws[((i << 1) | 1) & m]) != null) {
                    // 代表正在扫描中 这里允许跳出
                    if ((v.scanState & SCANNING) != 0)
                        break;
                    // 增加繁忙线程数量
                    ++nbusy;
                }
            }
            // 繁忙数 没有总线程数的2倍  或者 ctl 不为c 不能阻塞
            if (nbusy != (tc << 1) || ctl != c)
                canBlock = false;                 // unstable or stale
            // 总线程数 大于 并行数 且 活跃数 > 1   且调用者队列为null
            else if (tc >= pc && ac > 1 && w.isEmpty()) {
                // 减少活跃线程
                long nc = ((AC_MASK & (c - AC_UNIT)) |
                        (~AC_MASK & c));       // uncompensated
                canBlock = U.compareAndSwapLong(this, CTL, c, nc);
                // 如果超过最大线程数 抛出异常
            } else if (tc >= MAX_CAP ||
                    (this == common && tc >= pc + commonMaxSpares))
                throw new RejectedExecutionException(
                        "Thread limit exceeded replacing blocked worker");
            else {                                // similar to tryAddWorker
                boolean add = false;
                int rs;      // CAS within lock
                // 增加总的线程数
                long nc = ((AC_MASK & c) |
                        (TC_MASK & (c + TC_UNIT)));
                // 确保当前不是 STOP
                if (((rs = lockRunState()) & STOP) == 0)
                    add = U.compareAndSwapLong(this, CTL, c, nc);
                unlockRunState(rs, rs & ~RSLOCK);
                // 这里代表活跃线程数 不足需要创建一个新的线程
                canBlock = add && createWorker(); // throws on exception
            }
        }
        return canBlock;
    }

    /**
     * Helps and/or blocks until the given task is done or timeout.
     *
     * @param w        caller  任务所属的 队列
     * @param task     the task  被阻塞的任务
     * @param deadline for timed waits, if nonzero
     * @return task status on exit
     * 阻塞直到指定的任务完成
     */
    final int awaitJoin(WorkQueue w, ForkJoinTask<?> task, long deadline) {
        int s = 0;
        if (task != null && w != null) {
            // 获取当前等待join 的任务
            ForkJoinTask<?> prevJoin = w.currentJoin;
            // 将 currentJoin 替换成传入的 task 对象
            U.putOrderedObject(w, QCURRENTJOIN, task);
            // 判断 task 是否是 CC
            CountedCompleter<?> cc = (task instanceof CountedCompleter) ?
                    (CountedCompleter<?>) task : null;
            for (; ; ) {
                // 代表本次任务已经完成
                if ((s = task.status) < 0)
                    break;
                // 如果是cc 类型 从各个队列拉取 CC 任务并执行
                if (cc != null)
                    helpComplete(w, cc, 0);
                    // 如果没有任务 (base == top)  或者 tryRemoveAndExec == true 代表任务已经被移除 可能就是其他线程窃取了
                else if (w.base == w.top || w.tryRemoveAndExec(task))
                    // 帮助窃取者完成它的任务
                    helpStealer(w, task);
                // 如果任务已经完成
                if ((s = task.status) < 0)
                    break;
                long ms, ns;
                if (deadline == 0L)
                    ms = 0L;
                else if ((ns = deadline - System.nanoTime()) <= 0L)
                    break;
                else if ((ms = TimeUnit.NANOSECONDS.toMillis(ns)) <= 0L)
                    ms = 1L;
                // 尝试补偿  这里应该是 交换完成任务后(帮助偷取本任务的其他worker完成它的任务后)还有 多余的时间
                if (tryCompensate(w)) {
                    // 补偿成功 剩下的时间 进行阻塞
                    task.internalWait(ms);
                    // 更新活跃线程数
                    U.getAndAddLong(this, CTL, AC_UNIT);
                }
            }
            // 恢复原来 等待join的任务
            U.putOrderedObject(w, QCURRENTJOIN, prevJoin);
        }
        return s;
    }

    // Specialized scanning

    /**
     * Returns a (probably) non-empty steal queue, if one is found
     * during a scan, else null.  This method must be retried by
     * caller if, by the time it tries to use the queue, it is empty.
     */
    private WorkQueue findNonEmptyStealQueue() {
        WorkQueue[] ws;
        int m;  // one-shot version of scan loop
        int r = ThreadLocalRandom.nextSecondarySeed();
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0) {
            for (int origin = r & m, k = origin, oldSum = 0, checkSum = 0; ; ) {
                WorkQueue q;
                int b;
                if ((q = ws[k]) != null) {
                    if ((b = q.base) - q.top < 0)
                        return q;
                    checkSum += b;
                }
                if ((k = (k + 1) & m) == origin) {
                    if (oldSum == (oldSum = checkSum))
                        break;
                    checkSum = 0;
                }
            }
        }
        return null;
    }

    /**
     * Runs tasks until {@code isQuiescent()}. We piggyback on
     * active count ctl maintenance, but rather than blocking
     * when tasks cannot be found, we rescan until all others cannot
     * find tasks either.
     */
    final void helpQuiescePool(WorkQueue w) {
        ForkJoinTask<?> ps = w.currentSteal; // save context
        for (boolean active = true; ; ) {
            long c;
            WorkQueue q;
            ForkJoinTask<?> t;
            int b;
            w.execLocalTasks();     // run locals before each scan
            if ((q = findNonEmptyStealQueue()) != null) {
                if (!active) {      // re-establish active count
                    active = true;
                    U.getAndAddLong(this, CTL, AC_UNIT);
                }
                if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null) {
                    U.putOrderedObject(w, QCURRENTSTEAL, t);
                    t.doExec();
                    if (++w.nsteals < 0)
                        w.transferStealCount(this);
                }
            } else if (active) {      // decrement active count without queuing
                long nc = (AC_MASK & ((c = ctl) - AC_UNIT)) | (~AC_MASK & c);
                if ((int) (nc >> AC_SHIFT) + (config & SMASK) <= 0)
                    break;          // bypass decrement-then-increment
                if (U.compareAndSwapLong(this, CTL, c, nc))
                    active = false;
            } else if ((int) ((c = ctl) >> AC_SHIFT) + (config & SMASK) <= 0 &&
                    U.compareAndSwapLong(this, CTL, c, c + AC_UNIT))
                break;
        }
        U.putOrderedObject(w, QCURRENTSTEAL, ps);
    }

    /**
     * Gets and removes a local or stolen task for the given worker.
     *
     * @return a task, if available
     */
    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        for (ForkJoinTask<?> t; ; ) {
            WorkQueue q;
            int b;
            if ((t = w.nextLocalTask()) != null)
                return t;
            if ((q = findNonEmptyStealQueue()) == null)
                return null;
            if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null)
                return t;
        }
    }

    /**
     * Returns a cheap heuristic guide for task partitioning when
     * programmers, frameworks, tools, or languages have little or no
     * idea about task granularity.  In essence, by offering this
     * method, we ask users only about tradeoffs in overhead vs
     * expected throughput and its variance, rather than how finely to
     * partition tasks.
     * <p>
     * In a steady state strict (tree-structured) computation, each
     * thread makes available for stealing enough tasks for other
     * threads to remain active. Inductively, if all threads play by
     * the same rules, each thread should make available only a
     * constant number of tasks.
     * <p>
     * The minimum useful constant is just 1. But using a value of 1
     * would require immediate replenishment upon each steal to
     * maintain enough tasks, which is infeasible.  Further,
     * partitionings/granularities of offered tasks should minimize
     * steal rates, which in general means that threads nearer the top
     * of computation tree should generate more than those nearer the
     * bottom. In perfect steady state, each thread is at
     * approximately the same level of computation tree. However,
     * producing extra tasks amortizes the uncertainty of progress and
     * diffusion assumptions.
     * <p>
     * So, users will want to use values larger (but not much larger)
     * than 1 to both smooth over transient shortages and hedge
     * against uneven progress; as traded off against the cost of
     * extra task overhead. We leave the user to pick a threshold
     * value to compare with the results of this call to guide
     * decisions, but recommend values such as 3.
     * <p>
     * When all threads are active, it is on average OK to estimate
     * surplus strictly locally. In steady-state, if one thread is
     * maintaining say 2 surplus tasks, then so are others. So we can
     * just use estimated queue length.  However, this strategy alone
     * leads to serious mis-estimates in some non-steady-state
     * conditions (ramp-up, ramp-down, other stalls). We can detect
     * many of these by further considering the number of "idle"
     * threads, that are known to have zero queued tasks, so
     * compensate by a factor of (#idle/#active) threads.
     */
    static int getSurplusQueuedTaskCount() {
        Thread t;
        ForkJoinWorkerThread wt;
        ForkJoinPool pool;
        WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)) {
            int p = (pool = (wt = (ForkJoinWorkerThread) t).pool).
                    config & SMASK;
            int n = (q = wt.workQueue).top - q.base;
            int a = (int) (pool.ctl >> AC_SHIFT) + p;
            return n - (a > (p >>>= 1) ? 0 :
                    a > (p >>>= 1) ? 1 :
                            a > (p >>>= 1) ? 2 :
                                    a > (p >>>= 1) ? 4 :
                                            8);
        }
        return 0;
    }

    //  Termination

    /**
     * Possibly initiates and/or completes termination.
     *
     * @param now    if true, unconditionally terminate, else only
     *               if no work and no active workers
     * @param enable if true, enable shutdown when next possible
     * @return true if now terminating or terminated
     * 尝试终止线程池
     */
    private boolean tryTerminate(boolean now, boolean enable) {
        int rs;
        if (this == common)                       // cannot shut down
            return false;
        if ((rs = runState) >= 0) {
            if (!enable)
                return false;
            rs = lockRunState();                  // enter SHUTDOWN phase
            unlockRunState(rs, (rs & ~RSLOCK) | SHUTDOWN);
        }

        if ((rs & STOP) == 0) {
            if (!now) {                           // check quiescence
                for (long oldSum = 0L; ; ) {        // repeat until stable
                    WorkQueue[] ws;
                    WorkQueue w;
                    int m, b;
                    long c;
                    long checkSum = ctl;
                    if ((int) (checkSum >> AC_SHIFT) + (config & SMASK) > 0)
                        return false;             // still active workers
                    if ((ws = workQueues) == null || (m = ws.length - 1) <= 0)
                        break;                    // check queues
                    for (int i = 0; i <= m; ++i) {
                        if ((w = ws[i]) != null) {
                            if ((b = w.base) != w.top || w.scanState >= 0 ||
                                    w.currentSteal != null) {
                                tryRelease(c = ctl, ws[m & (int) c], AC_UNIT);
                                return false;     // arrange for recheck
                            }
                            checkSum += b;
                            if ((i & 1) == 0)
                                w.qlock = -1;     // try to disable external
                        }
                    }
                    if (oldSum == (oldSum = checkSum))
                        break;
                }
            }
            if ((runState & STOP) == 0) {
                rs = lockRunState();              // enter STOP phase
                unlockRunState(rs, (rs & ~RSLOCK) | STOP);
            }
        }

        int pass = 0;                             // 3 passes to help terminate
        for (long oldSum = 0L; ; ) {                // or until done or stable
            WorkQueue[] ws;
            WorkQueue w;
            ForkJoinWorkerThread wt;
            int m;
            long checkSum = ctl;
            if ((short) (checkSum >>> TC_SHIFT) + (config & SMASK) <= 0 ||
                    (ws = workQueues) == null || (m = ws.length - 1) <= 0) {
                if ((runState & TERMINATED) == 0) {
                    rs = lockRunState();          // done
                    unlockRunState(rs, (rs & ~RSLOCK) | TERMINATED);
                    synchronized (this) {
                        notifyAll();
                    } // for awaitTermination
                }
                break;
            }
            for (int i = 0; i <= m; ++i) {
                if ((w = ws[i]) != null) {
                    checkSum += w.base;
                    w.qlock = -1;                 // try to disable
                    if (pass > 0) {
                        w.cancelAll();            // clear queue
                        if (pass > 1 && (wt = w.owner) != null) {
                            if (!wt.isInterrupted()) {
                                try {             // unblock join
                                    wt.interrupt();
                                } catch (Throwable ignore) {
                                }
                            }
                            if (w.scanState < 0)
                                U.unpark(wt);     // wake up
                        }
                    }
                }
            }
            if (checkSum != oldSum) {             // unstable
                oldSum = checkSum;
                pass = 0;
            } else if (pass > 3 && pass > m)        // can't further help
                break;
            else if (++pass > 1) {                // try to dequeue
                long c;
                int j = 0, sp;            // bound attempts
                while (j++ <= m && (sp = (int) (c = ctl)) != 0)
                    tryRelease(c, ws[sp & m], AC_UNIT);
            }
        }
        return true;
    }

    // External operations

    /**
     * Full version of externalPush, handling uncommon cases, as well
     * as performing secondary initialization upon the first
     * submission of the first task to the pool.  It also detects
     * first submission by an external thread and creates a new shared
     * queue if the one at index if empty or contended.
     *
     * @param task the task. Caller must ensure non-null.
     *             该方法相当于是一个完整版的 externalPush
     */
    private void externalSubmit(ForkJoinTask<?> task) {
        int r;                                    // initialize caller's probe
        // 初始化探针值  该值作为 随机获取 wq 的参数
        if ((r = ThreadLocalRandom.getProbe()) == 0) {
            // 初始化 probe
            ThreadLocalRandom.localInit();
            // 这个值可以看作一个散列函数 某个线程每次从外部将任务设置到forkJoin 中 总是会插入到对应的wq 除非当前wq 无法再插入任务
            r = ThreadLocalRandom.getProbe();
        }
        for (; ; ) {
            WorkQueue[] ws;
            WorkQueue q;
            int rs, m, k;
            boolean move = false;
            // 当运行状态 小于 0 的时候代表线程池已经终止
            // 首次初始化 时 runState = 0 会进入第二个分支 第二次  runState 就会变成4 会进入 第4个分支
            if ((rs = runState) < 0) {
                // 这时 查看是否需要终止线程池
                tryTerminate(false, false);     // help terminate
                throw new RejectedExecutionException();
                // 这里代表任务队列本身还没有被初始化
            } else if ((rs & STARTED) == 0 ||     // initialize
                    ((ws = workQueues) == null || (m = ws.length - 1) < 0)) {
                int ns = 0;
                // 加锁
                rs = lockRunState();
                try {
                    if ((rs & STARTED) == 0) {
                        // 初始化任务窃取数量
                        U.compareAndSwapObject(this, STEALCOUNTER, null,
                                new AtomicLong());
                        // create workQueues array with size a power of two
                        // 按照 2的幂次 来初始化 workQueue 数组对象
                        // p 就是构造时使用的并行度  SMASK 代表一个掩码 应该是 并行度最大值
                        int p = config & SMASK; // ensure at least 2 slots
                        // 通过位运算保证 是2的幂次
                        int n = (p > 1) ? p - 1 : 1;
                        n |= n >>> 1;
                        n |= n >>> 2;
                        n |= n >>> 4;
                        n |= n >>> 8;
                        n |= n >>> 16;
                        n = (n + 1) << 1;
                        workQueues = new WorkQueue[n];
                        // 设置成 初始化完成
                        ns = STARTED;
                    }
                } finally {
                    // 更新状态 并解锁
                    unlockRunState(rs, (rs & ~RSLOCK) | ns);
                }
                // 通过下面的分支 填充slot后开始提交任务
            } else if ((q = ws[k = r & m & SQMASK]) != null) {
                // 加锁
                if (q.qlock == 0 && U.compareAndSwapInt(q, QLOCK, 0, 1)) {
                    ForkJoinTask<?>[] a = q.array;
                    int s = q.top;
                    boolean submitted = false; // initial submission or resizing
                    try {                      // locked version of push
                        if ((a != null && a.length > s + 1 - q.base) ||
                                // 尝试初始化 a 或者进行扩容
                                (a = q.growArray()) != null) {
                            // 在 top插入新元素
                            int j = (((a.length - 1) & s) << ASHIFT) + ABASE;
                            U.putOrderedObject(a, j, task);
                            U.putOrderedInt(q, QTOP, s + 1);
                            submitted = true;
                        }
                    } finally {
                        U.compareAndSwapInt(q, QLOCK, 1, 0);
                    }
                    // 这里代表 从外部线程提交任务 在  FJ 内部构建了 偶数位的 wq 对象 并成功在top的位置设置了任务 现在需要一个线程去执行任务了 (使用FJ 内部线程去执行)
                    // 外部线程提交任务后 会将控制权返回给用户（非阻塞）
                    if (submitted) {
                        // 因为这里是首次初始化 所以需要唤醒(工作)线程 or 新增线程
                        signalWork(ws, q);
                        return;
                    }
                }
                // 操作失败 需要重新获取探针
                move = true;                   // move on failure
                // 这里代表 wqs 下 对应的 wq 对象还没有初始化 初始化完成才会进入上面的分支
            } else if (((rs = runState) & RSLOCK) == 0) { // create new queue
                // 针对外部任务 引起的初始化 workQueue 是不会设置 owner的 也就是共享模式 可以被多个线程偷取任务
                q = new WorkQueue(this, null);
                // 创建一个新的工作队列对象 并设置探针值 作为 起始的随机值 之后从那个任务队列中偷取任务 就是靠这个随机值来记录下标的
                q.hint = r;
                // wq 的 config 记录的是 该 wq 在整个wqs 中的下标 以及mode 信息 外部线程的mode 始终是 SHARED_QUEUE
                q.config = k | SHARED_QUEUE;
                // 外部线程创建的 wq 对象 scanState 始终是 INACTIVE 只能等待FJThread 去偷取任务
                q.scanState = INACTIVE;
                // 锁定当前运行状态
                rs = lockRunState();           // publish index
                // rs > 0 代表当前线程池 已经启动
                // ws != null 代表队列已经被创建
                // ws[k] == null  这里要确定 wqs 中对应的slot 还没有被占据
                if (rs > 0 && (ws = workQueues) != null &&
                        k < ws.length && ws[k] == null)
                    ws[k] = q;                 // else terminated
                unlockRunState(rs, rs & ~RSLOCK);
            } else
                // 代表竞争激烈 重新设置探针值  方便之后 选择 wqs 的其他slot
                move = true;                   // move if busy
            if (move)
                // 重新设置探针值
                r = ThreadLocalRandom.advanceProbe(r);
        }
    }

    /**
     * Tries to add the given task to a submission queue at
     * submitter's current queue. Only the (vastly) most common path
     * is directly handled in this method, while screening for need
     * for externalSubmit.
     *
     * @param task the task. Caller must ensure non-null.
     *             从外部线程将某个任务提交到线程池中  submit execute invoke 都是通过该方法
     *             外部推送的任务 一定是在偶数的某个workQueue中
     */
    final void externalPush(ForkJoinTask<?> task) {
        // 工作队列数组 当前工作者
        WorkQueue[] ws;
        WorkQueue q;
        int m;
        // 返回当前线程的 探针 可以理解为 随机分配任务到哪个队列的因子
        int r = ThreadLocalRandom.getProbe();
        // 获取当前运行状态
        int rs = runState;
        // 首先确定添加任务的外部线程的 探针值 能否通过hash 算法获取到上次提交的wq 如果找到的话 直接往队列中插入任务
        if ((ws = workQueues) != null && (m = (ws.length - 1)) >= 0 &&
                // 获取偶数位wq
                (q = ws[m & r & SQMASK]) != null && r != 0 && rs > 0 &&
                // 将本任务的 q 设置成 1 代表加锁
                U.compareAndSwapInt(q, QLOCK, 0, 1)) {
            ForkJoinTask<?>[] a;
            int am, n, s;
            // 确保数据正常
            if ((a = q.array) != null &&
                    (am = a.length - 1) > (n = (s = q.top) - q.base)) {
                // top代表下个要插入任务的下标 这里 将外部线程传入的 task 通过某种散列算法后找到一个 合适的 WorkQueue 之后将任务添加进去(相同线程会匹配到同一个队列)
                // 如果ws 长度发生变化 好像会更换位置 不过这样设计的核心就是 尽量让任务分散
                int j = ((am & s) << ASHIFT) + ABASE;
                U.putOrderedObject(a, j, task);
                U.putOrderedInt(q, QTOP, s + 1);
                U.putIntVolatile(q, QLOCK, 0);
                // 代表任务从0 到 1 这时需要唤醒线程 (如果线程不够会新建线程)
                if (n <= 1)
                    signalWork(ws, q);
                return;
            }
            U.compareAndSwapInt(q, QLOCK, 1, 0);
        }
        // 这里进行workQueues 的初始化 因为上面的push 必须先满足 pool对象本身核心的属性已经被初始化
        externalSubmit(task);
    }

    /**
     * Returns common pool queue for an external thread.
     */
    static WorkQueue commonSubmitterQueue() {
        ForkJoinPool p = common;
        int r = ThreadLocalRandom.getProbe();
        WorkQueue[] ws;
        int m;
        return (p != null && (ws = p.workQueues) != null &&
                (m = ws.length - 1) >= 0) ?
                ws[m & r & SQMASK] : null;
    }

    /**
     * Performs tryUnpush for an external submitter: Finds queue,
     * locks if apparently non-empty, validates upon locking, and
     * adjusts top. Each check can fail but rarely does.
     * 外部线程调用指定某个任务 尝试从wq 中弹出并执行
     */
    final boolean tryExternalUnpush(ForkJoinTask<?> task) {
        WorkQueue[] ws;
        WorkQueue w;
        ForkJoinTask<?>[] a;
        int m, s;
        // 通过获取线程的某个特定值 作为散列因子计算下标
        int r = ThreadLocalRandom.getProbe();
        // 首先确定 数据是合理的
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 &&
                (w = ws[m & r & SQMASK]) != null &&
                (a = w.array) != null && (s = w.top) != w.base) {
            // 获取顶部元素
            long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
            if (U.compareAndSwapInt(w, QLOCK, 0, 1)) {
                if (w.top == s && w.array == a &&
                        // 确保首元素是 目标task
                        U.getObject(a, j) == task &&
                        U.compareAndSwapObject(a, j, task, null)) {
                    U.putOrderedInt(w, QTOP, s - 1);
                    U.putOrderedInt(w, QLOCK, 0);
                    return true;
                }
                U.compareAndSwapInt(w, QLOCK, 1, 0);
            }
        }
        return false;
    }

    /**
     * Performs helpComplete for an external submitter.
     * 针对外部线程提交的任务 协助执行 CC
     */
    final int externalHelpComplete(CountedCompleter<?> task, int maxTasks) {
        WorkQueue[] ws;
        int n;
        // 获取随机因子
        int r = ThreadLocalRandom.getProbe();
        // 前面的条件代表 pool 是否完成初始化 如果还没有完成初始化 返回0 代表还没完成任何任务
        return ((ws = workQueues) == null || (n = ws.length) == 0) ? 0 :
                // 否则 通过某种散列算法 获取到一个下标值 然后从ws 中找到对应的 workQueue 对象  之后调用helpCC
                helpComplete(ws[(n - 1) & r & SQMASK], task, maxTasks);
    }

    // Exported methods

    // Constructors

    /**
     * Creates a {@code ForkJoinPool} with parallelism equal to {@link
     * java.lang.Runtime#availableProcessors}, using the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @throws SecurityException if a security manager exists and
     *                           the caller is not permitted to modify threads
     *                           because it does not hold {@link
     *                           java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool() {
        this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
                defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the indicated parallelism
     * level, the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @param parallelism the parallelism level
     * @throws IllegalArgumentException if parallelism less than or
     *                                  equal to zero, or greater than implementation limit
     * @throws SecurityException        if a security manager exists and
     *                                  the caller is not permitted to modify threads
     *                                  because it does not hold {@link
     *                                  java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters.
     *
     * @param parallelism the parallelism level. For default value,
     *                    use {@link java.lang.Runtime#availableProcessors}.
     * @param factory     the factory for creating new threads. For default value,
     *                    use {@link #defaultForkJoinWorkerThreadFactory}.
     * @param handler     the handler for internal worker threads that
     *                    terminate due to unrecoverable errors encountered while executing
     *                    tasks. For default value, use {@code null}.
     * @param asyncMode   if true,
     *                    establishes local first-in-first-out scheduling mode for forked
     *                    tasks that are never joined. This mode may be more appropriate
     *                    than default locally stack-based mode in applications in which
     *                    worker threads only process event-style asynchronous tasks.
     *                    For default value, use {@code false}.
     * @throws IllegalArgumentException if parallelism less than or
     *                                  equal to zero, or greater than implementation limit
     * @throws NullPointerException     if the factory is null
     * @throws SecurityException        if a security manager exists and
     *                                  the caller is not permitted to modify threads
     *                                  because it does not hold {@link
     *                                  java.lang.RuntimePermission}{@code ("modifyThread")}
     *                                  构造函数
     */
    public ForkJoinPool(int parallelism, // 代表并行度 默认为CPU 核数
                        ForkJoinWorkerThreadFactory factory, // 构建 FJthread 的线程工厂
                        UncaughtExceptionHandler handler, // 全局性的异常处理器  默认为null
                        boolean asyncMode) { // 是否为异步模式  默认为false 如果为true 表示子任务遵循先进先出的顺序 且任务不能被合并 为什么会跟 顺序有关系???
        this(checkParallelism(parallelism),
                checkFactory(factory),
                handler,
                asyncMode ? FIFO_QUEUE : LIFO_QUEUE,
                "ForkJoinPool-" + nextPoolId() + "-worker-");
        checkPermission();
    }

    private static int checkParallelism(int parallelism) {
        if (parallelism <= 0 || parallelism > MAX_CAP)
            throw new IllegalArgumentException();
        return parallelism;
    }

    private static ForkJoinWorkerThreadFactory checkFactory
            (ForkJoinWorkerThreadFactory factory) {
        if (factory == null)
            throw new NullPointerException();
        return factory;
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters, without
     * any security checks or parameter validation.  Invoked directly by
     * makeCommonPool.
     * 初始化 forkjoin 线程池对象 注意该构造函数 是 private
     * 也可以显示构建forkjoinPool
     *
     * @param parallelism      代表线程池的并行度
     * @param factory          代表线程工厂
     * @param handler          代表异常处理器
     * @param mode             代表使用的队列模式
     * @param workerNamePrefix 代表生成的线程名前缀
     */
    private ForkJoinPool(int parallelism,
                         ForkJoinWorkerThreadFactory factory,
                         UncaughtExceptionHandler handler,
                         int mode,
                         String workerNamePrefix) {
        this.workerNamePrefix = workerNamePrefix;
        this.factory = factory;
        this.ueh = handler;
        // 代表 后15位都是 并行度相关信息 | mode 代表配置信息    mode 默认是0
        this.config = (parallelism & SMASK) | mode;
        // np 代表 负的并行度???
        long np = (long) (-parallelism); // offset ctl counts
        this.ctl = ((np << AC_SHIFT) & AC_MASK) | ((np << TC_SHIFT) & TC_MASK);
    }

    /**
     * Returns the common pool instance. This pool is statically
     * constructed; its run state is unaffected by attempts to {@link
     * #shutdown} or {@link #shutdownNow}. However this pool and any
     * ongoing processing are automatically terminated upon program
     * {@link System#exit}.  Any program that relies on asynchronous
     * task processing to complete before program termination should
     * invoke {@code commonPool().}{@link #awaitQuiescence awaitQuiescence},
     * before exit.
     *
     * @return the common pool instance
     * @since 1.8
     */
    public static ForkJoinPool commonPool() {
        // assert common != null : "static init error";
        return common;
    }

    // Execution methods

    /**
     * Performs the given task, returning its result upon completion.
     * If the computation encounters an unchecked Exception or Error,
     * it is rethrown as the outcome of this invocation.  Rethrown
     * exceptions behave in the same way as regular exceptions, but,
     * when possible, contain stack traces (as displayed for example
     * using {@code ex.printStackTrace()}) of both the current thread
     * as well as the thread actually encountering the exception;
     * minimally only the latter.
     *
     * @param task the task
     * @param <T>  the type of the task's result
     * @return the task's result
     * @throws NullPointerException       if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *                                    scheduled for execution
     *                                    invoke 本身在线程池种 意味着 执行某个任务并等待返回结果
     */
    public <T> T invoke(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
        return task.join();
    }

    /**
     * Arranges for (asynchronous) execution of the given task.
     *
     * @param task the task
     * @throws NullPointerException       if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *                                    scheduled for execution
     *                                    代表执行某个任务 而不考虑返回结果 通过externalPush 从外部推送任务到线程池中
     */
    public void execute(ForkJoinTask<?> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
    }

    // AbstractExecutorService methods

    /**
     * @throws NullPointerException       if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *                                    scheduled for execution
     *                                    使用forkjoin 线程池 执行一个普通任务
     */
    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            // 将 task 包装成 forkjointask
            job = new ForkJoinTask.RunnableExecuteAction(task);
        // 代表外部线程 将 封装后的 task 推入某个数据结构中
        externalPush(job);
    }

    /**
     * Submits a ForkJoinTask for execution.
     *
     * @param task the task to submit
     * @param <T>  the type of the task's result
     * @return the task
     * @throws NullPointerException       if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *                                    scheduled for execution
     *                                    执行某个任务并返回一个 Future 对象
     */
    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
        return task;
    }

    /**
     * @throws NullPointerException       if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *                                    scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedCallable<T>(task);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException       if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *                                    scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedRunnable<T>(task, result);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException       if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *                                    scheduled for execution
     */
    public ForkJoinTask<?> submit(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = new ForkJoinTask.AdaptedRunnableAction(task);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException       {@inheritDoc}
     * @throws RejectedExecutionException {@inheritDoc}
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        // In previous versions of this class, this method constructed
        // a task to run ForkJoinTask.invokeAll, but now external
        // invocation of multiple tasks is at least as efficient.
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());

        boolean done = false;
        try {
            for (Callable<T> t : tasks) {
                ForkJoinTask<T> f = new ForkJoinTask.AdaptedCallable<T>(t);
                futures.add(f);
                externalPush(f);
            }
            for (int i = 0, size = futures.size(); i < size; i++)
                ((ForkJoinTask<?>) futures.get(i)).quietlyJoin();
            done = true;
            return futures;
        } finally {
            if (!done)
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(false);
        }
    }

    /**
     * Returns the factory used for constructing new workers.
     *
     * @return the factory used for constructing new workers
     */
    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    /**
     * Returns the handler for internal worker threads that terminate
     * due to unrecoverable errors encountered while executing tasks.
     *
     * @return the handler, or {@code null} if none
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    /**
     * Returns the targeted parallelism level of this pool.
     *
     * @return the targeted parallelism level of this pool
     */
    public int getParallelism() {
        int par;
        return ((par = config & SMASK) > 0) ? par : 1;
    }

    /**
     * Returns the targeted parallelism level of the common pool.
     *
     * @return the targeted parallelism level of the common pool
     * @since 1.8
     */
    public static int getCommonPoolParallelism() {
        return commonParallelism;
    }

    /**
     * Returns the number of worker threads that have started but not
     * yet terminated.  The result returned by this method may differ
     * from {@link #getParallelism} when threads are created to
     * maintain parallelism when others are cooperatively blocked.
     *
     * @return the number of worker threads
     */
    public int getPoolSize() {
        return (config & SMASK) + (short) (ctl >>> TC_SHIFT);
    }

    /**
     * Returns {@code true} if this pool uses local first-in-first-out
     * scheduling mode for forked tasks that are never joined.
     *
     * @return {@code true} if this pool uses async mode
     */
    public boolean getAsyncMode() {
        return (config & FIFO_QUEUE) != 0;
    }

    /**
     * Returns an estimate of the number of worker threads that are
     * not blocked waiting to join tasks or for other managed
     * synchronization. This method may overestimate the
     * number of running threads.
     *
     * @return the number of worker threads
     */
    public int getRunningThreadCount() {
        int rc = 0;
        WorkQueue[] ws;
        WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && w.isApparentlyUnblocked())
                    ++rc;
            }
        }
        return rc;
    }

    /**
     * Returns an estimate of the number of threads that are currently
     * stealing or executing tasks. This method may overestimate the
     * number of active threads.
     *
     * @return the number of active threads
     */
    public int getActiveThreadCount() {
        int r = (config & SMASK) + (int) (ctl >> AC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    /**
     * Returns {@code true} if all worker threads are currently idle.
     * An idle worker is one that cannot obtain a task to execute
     * because none are available to steal from other threads, and
     * there are no pending submissions to the pool. This method is
     * conservative; it might not return {@code true} immediately upon
     * idleness of all threads, but will eventually become true if
     * threads remain inactive.
     *
     * @return {@code true} if all threads are currently idle
     */
    public boolean isQuiescent() {
        return (config & SMASK) + (int) (ctl >> AC_SHIFT) <= 0;
    }

    /**
     * Returns an estimate of the total number of tasks stolen from
     * one thread's work queue by another. The reported value
     * underestimates the actual total number of steals when the pool
     * is not quiescent. This value may be useful for monitoring and
     * tuning fork/join programs: in general, steal counts should be
     * high enough to keep threads busy, but low enough to avoid
     * overhead and contention across threads.
     *
     * @return the number of steals
     */
    public long getStealCount() {
        AtomicLong sc = stealCounter;
        long count = (sc == null) ? 0L : sc.get();
        WorkQueue[] ws;
        WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.nsteals;
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the total number of tasks currently held
     * in queues by worker threads (but not including tasks submitted
     * to the pool that have not begun executing). This value is only
     * an approximation, obtained by iterating across all threads in
     * the pool. This method may be useful for tuning task
     * granularities.
     *
     * @return the number of queued tasks
     */
    public long getQueuedTaskCount() {
        long count = 0;
        WorkQueue[] ws;
        WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the number of tasks submitted to this
     * pool that have not yet begun executing.  This method may take
     * time proportional to the number of submissions.
     *
     * @return the number of queued submissions
     */
    public int getQueuedSubmissionCount() {
        int count = 0;
        WorkQueue[] ws;
        WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if there are any tasks submitted to this
     * pool that have not yet begun executing.
     *
     * @return {@code true} if there are any queued submissions
     */
    public boolean hasQueuedSubmissions() {
        WorkQueue[] ws;
        WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && !w.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the next unexecuted submission if one is
     * available.  This method may be useful in extensions to this
     * class that re-assign work in systems with multiple pools.
     *
     * @return the next submission, or {@code null} if none
     */
    protected ForkJoinTask<?> pollSubmission() {
        WorkQueue[] ws;
        WorkQueue w;
        ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && (t = w.poll()) != null)
                    return t;
            }
        }
        return null;
    }

    /**
     * Removes all available unexecuted submitted and forked tasks
     * from scheduling queues and adds them to the given collection,
     * without altering their execution status. These may include
     * artificially generated or wrapped tasks. This method is
     * designed to be invoked only when the pool is known to be
     * quiescent. Invocations at other times may not remove all
     * tasks. A failure encountered while attempting to add elements
     * to collection {@code c} may result in elements being in
     * neither, either or both collections when the associated
     * exception is thrown.  The behavior of this operation is
     * undefined if the specified collection is modified while the
     * operation is in progress.
     *
     * @param c the collection to transfer elements into
     * @return the number of elements transferred
     */
    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int count = 0;
        WorkQueue[] ws;
        WorkQueue w;
        ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    while ((t = w.poll()) != null) {
                        c.add(t);
                        ++count;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state, parallelism level, and
     * worker and task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        // Use a single pass through workQueues to collect counts
        long qt = 0L, qs = 0L;
        int rc = 0;
        AtomicLong sc = stealCounter;
        long st = (sc == null) ? 0L : sc.get();
        long c = ctl;
        WorkQueue[] ws;
        WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    int size = w.queueSize();
                    if ((i & 1) == 0)
                        qs += size;
                    else {
                        qt += size;
                        st += w.nsteals;
                        if (w.isApparentlyUnblocked())
                            ++rc;
                    }
                }
            }
        }
        int pc = (config & SMASK);
        int tc = pc + (short) (c >>> TC_SHIFT);
        int ac = pc + (int) (c >> AC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        int rs = runState;
        String level = ((rs & TERMINATED) != 0 ? "Terminated" :
                (rs & STOP) != 0 ? "Terminating" :
                        (rs & SHUTDOWN) != 0 ? "Shutting down" :
                                "Running");
        return super.toString() +
                "[" + level +
                ", parallelism = " + pc +
                ", size = " + tc +
                ", active = " + ac +
                ", running = " + rc +
                ", steals = " + st +
                ", tasks = " + qt +
                ", submissions = " + qs +
                "]";
    }

    /**
     * Possibly initiates an orderly shutdown in which previously
     * submitted tasks are executed, but no new tasks will be
     * accepted. Invocation has no effect on execution state if this
     * is the {@link #commonPool()}, and no additional effect if
     * already shut down.  Tasks that are in the process of being
     * submitted concurrently during the course of this method may or
     * may not be rejected.
     *
     * @throws SecurityException if a security manager exists and
     *                           the caller is not permitted to modify threads
     *                           because it does not hold {@link
     *                           java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public void shutdown() {
        checkPermission();
        tryTerminate(false, true);
    }

    /**
     * Possibly attempts to cancel and/or stop all tasks, and reject
     * all subsequently submitted tasks.  Invocation has no effect on
     * execution state if this is the {@link #commonPool()}, and no
     * additional effect if already shut down. Otherwise, tasks that
     * are in the process of being submitted or executed concurrently
     * during the course of this method may or may not be
     * rejected. This method cancels both existing and unexecuted
     * tasks, in order to permit termination in the presence of task
     * dependencies. So the method always returns an empty list
     * (unlike the case for some other Executors).
     *
     * @return an empty list
     * @throws SecurityException if a security manager exists and
     *                           the caller is not permitted to modify threads
     *                           because it does not hold {@link
     *                           java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public List<Runnable> shutdownNow() {
        checkPermission();
        tryTerminate(true, true);
        return Collections.emptyList();
    }

    /**
     * Returns {@code true} if all tasks have completed following shut down.
     *
     * @return {@code true} if all tasks have completed following shut down
     */
    public boolean isTerminated() {
        return (runState & TERMINATED) != 0;
    }

    /**
     * Returns {@code true} if the process of termination has
     * commenced but not yet completed.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, or are waiting for I/O,
     * causing this executor not to properly terminate. (See the
     * advisory notes for class {@link ForkJoinTask} stating that
     * tasks should not normally entail blocking operations.  But if
     * they do, they must abort them on interrupt.)
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int rs = runState;
        return (rs & STOP) != 0 && (rs & TERMINATED) == 0;
    }

    /**
     * Returns {@code true} if this pool has been shut down.
     *
     * @return {@code true} if this pool has been shut down
     */
    public boolean isShutdown() {
        return (runState & SHUTDOWN) != 0;
    }

    /**
     * Blocks until all tasks have completed execution after a
     * shutdown request, or the timeout occurs, or the current thread
     * is interrupted, whichever happens first. Because the {@link
     * #commonPool()} never terminates until program shutdown, when
     * applied to the common pool, this method is equivalent to {@link
     * #awaitQuiescence(long, TimeUnit)} but always returns {@code false}.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     * {@code false} if the timeout elapsed before termination
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (this == common) {
            awaitQuiescence(timeout, unit);
            return false;
        }
        long nanos = unit.toNanos(timeout);
        if (isTerminated())
            return true;
        if (nanos <= 0L)
            return false;
        long deadline = System.nanoTime() + nanos;
        synchronized (this) {
            for (; ; ) {
                if (isTerminated())
                    return true;
                if (nanos <= 0L)
                    return false;
                long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                wait(millis > 0L ? millis : 1L);
                nanos = deadline - System.nanoTime();
            }
        }
    }

    /**
     * If called by a ForkJoinTask operating in this pool, equivalent
     * in effect to {@link ForkJoinTask#helpQuiesce}. Otherwise,
     * waits and/or attempts to assist performing tasks until this
     * pool {@link #isQuiescent} or the indicated timeout elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if quiescent; {@code false} if the
     * timeout elapsed.
     */
    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long nanos = unit.toNanos(timeout);
        ForkJoinWorkerThread wt;
        Thread thread = Thread.currentThread();
        if ((thread instanceof ForkJoinWorkerThread) &&
                (wt = (ForkJoinWorkerThread) thread).pool == this) {
            helpQuiescePool(wt.workQueue);
            return true;
        }
        long startTime = System.nanoTime();
        WorkQueue[] ws;
        int r = 0, m;
        boolean found = true;
        while (!isQuiescent() && (ws = workQueues) != null &&
                (m = ws.length - 1) >= 0) {
            if (!found) {
                if ((System.nanoTime() - startTime) > nanos)
                    return false;
                Thread.yield(); // cannot block
            }
            found = false;
            for (int j = (m + 1) << 2; j >= 0; --j) {
                ForkJoinTask<?> t;
                WorkQueue q;
                int b, k;
                if ((k = r++ & m) <= m && k >= 0 && (q = ws[k]) != null &&
                        (b = q.base) - q.top < 0) {
                    found = true;
                    if ((t = q.pollAt(b)) != null)
                        t.doExec();
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Waits and/or attempts to assist performing tasks indefinitely
     * until the {@link #commonPool()} {@link #isQuiescent}.
     */
    static void quiesceCommonPool() {
        common.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Interface for extending managed parallelism for tasks running
     * in {@link ForkJoinPool}s.
     *
     * <p>A {@code ManagedBlocker} provides two methods.  Method
     * {@link #isReleasable} must return {@code true} if blocking is
     * not necessary. Method {@link #block} blocks the current thread
     * if necessary (perhaps internally invoking {@code isReleasable}
     * before actually blocking). These actions are performed by any
     * thread invoking {@link ForkJoinPool#managedBlock(ManagedBlocker)}.
     * The unusual methods in this API accommodate synchronizers that
     * may, but don't usually, block for long periods. Similarly, they
     * allow more efficient internal handling of cases in which
     * additional workers may be, but usually are not, needed to
     * ensure sufficient parallelism.  Toward this end,
     * implementations of method {@code isReleasable} must be amenable
     * to repeated invocation.
     *
     * <p>For example, here is a ManagedBlocker based on a
     * ReentrantLock:
     * <pre> {@code
     * class ManagedLocker implements ManagedBlocker {
     *   final ReentrantLock lock;
     *   boolean hasLock = false;
     *   ManagedLocker(ReentrantLock lock) { this.lock = lock; }
     *   public boolean block() {
     *     if (!hasLock)
     *       lock.lock();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return hasLock || (hasLock = lock.tryLock());
     *   }
     * }}</pre>
     *
     * <p>Here is a class that possibly blocks waiting for an
     * item on a given queue:
     * <pre> {@code
     * class QueueTaker<E> implements ManagedBlocker {
     *   final BlockingQueue<E> queue;
     *   volatile E item = null;
     *   QueueTaker(BlockingQueue<E> q) { this.queue = q; }
     *   public boolean block() throws InterruptedException {
     *     if (item == null)
     *       item = queue.take();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return item != null || (item = queue.poll()) != null;
     *   }
     *   public E getItem() { // call after pool.managedBlock completes
     *     return item;
     *   }
     * }}</pre>
     */
    public static interface ManagedBlocker {
        /**
         * Possibly blocks the current thread, for example waiting for
         * a lock or condition.
         *
         * @return {@code true} if no additional blocking is necessary
         * (i.e., if isReleasable would return true)
         * @throws InterruptedException if interrupted while waiting
         *                              (the method is not required to do so, but is allowed to)
         */
        boolean block() throws InterruptedException;

        /**
         * Returns {@code true} if blocking is unnecessary.
         *
         * @return {@code true} if blocking is unnecessary
         */
        boolean isReleasable();
    }

    /**
     * Runs the given possibly blocking task.  When {@linkplain
     * ForkJoinTask#inForkJoinPool() running in a ForkJoinPool}, this
     * method possibly arranges for a spare thread to be activated if
     * necessary to ensure sufficient parallelism while the current
     * thread is blocked in {@link ManagedBlocker#block blocker.block()}.
     *
     * <p>This method repeatedly calls {@code blocker.isReleasable()} and
     * {@code blocker.block()} until either method returns {@code true}.
     * Every call to {@code blocker.block()} is preceded by a call to
     * {@code blocker.isReleasable()} that returned {@code false}.
     *
     * <p>If not running in a ForkJoinPool, this method is
     * behaviorally equivalent to
     * <pre> {@code
     * while (!blocker.isReleasable())
     *   if (blocker.block())
     *     break;}</pre>
     * <p>
     * If running in a ForkJoinPool, the pool may first be expanded to
     * ensure sufficient parallelism available during the call to
     * {@code blocker.block()}.
     *
     * @param blocker the blocker task
     * @throws InterruptedException if {@code blocker.block()} did so
     */
    public static void managedBlock(ManagedBlocker blocker)
            throws InterruptedException {
        ForkJoinPool p;
        ForkJoinWorkerThread wt;
        Thread t = Thread.currentThread();
        if ((t instanceof ForkJoinWorkerThread) &&
                (p = (wt = (ForkJoinWorkerThread) t).pool) != null) {
            WorkQueue w = wt.workQueue;
            while (!blocker.isReleasable()) {
                if (p.tryCompensate(w)) {
                    try {
                        do {
                        } while (!blocker.isReleasable() &&
                                !blocker.block());
                    } finally {
                        U.getAndAddLong(p, CTL, AC_UNIT);
                    }
                    break;
                }
            }
        } else {
            do {
            } while (!blocker.isReleasable() &&
                    !blocker.block());
        }
    }

    // AbstractExecutorService overrides.  These rely on undocumented
    // fact that ForkJoinTask.adapt returns ForkJoinTasks that also
    // implement RunnableFuture.

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.AdaptedRunnable<T>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.AdaptedCallable<T>(callable);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final int ABASE;
    private static final int ASHIFT;
    private static final long CTL;
    private static final long RUNSTATE;
    private static final long STEALCOUNTER;
    private static final long PARKBLOCKER;
    private static final long QTOP;
    private static final long QLOCK;
    private static final long QSCANSTATE;
    private static final long QPARKER;
    private static final long QCURRENTSTEAL;
    private static final long QCURRENTJOIN;

    /**
     * 该 对象在初始化时 触发的静态块
     */
    static {
        // initialize field offsets for CAS etc
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = ForkJoinPool.class;
            CTL = U.objectFieldOffset
                    (k.getDeclaredField("ctl"));
            RUNSTATE = U.objectFieldOffset
                    (k.getDeclaredField("runState"));
            STEALCOUNTER = U.objectFieldOffset
                    (k.getDeclaredField("stealCounter"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                    (tk.getDeclaredField("parkBlocker"));
            Class<?> wk = WorkQueue.class;
            QTOP = U.objectFieldOffset
                    (wk.getDeclaredField("top"));
            QLOCK = U.objectFieldOffset
                    (wk.getDeclaredField("qlock"));
            QSCANSTATE = U.objectFieldOffset
                    (wk.getDeclaredField("scanState"));
            QPARKER = U.objectFieldOffset
                    (wk.getDeclaredField("parker"));
            QCURRENTSTEAL = U.objectFieldOffset
                    (wk.getDeclaredField("currentSteal"));
            QCURRENTJOIN = U.objectFieldOffset
                    (wk.getDeclaredField("currentJoin"));
            Class<?> ak = ForkJoinTask[].class;
            ABASE = U.arrayBaseOffset(ak);
            int scale = U.arrayIndexScale(ak);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception e) {
            throw new Error(e);
        }

        commonMaxSpares = DEFAULT_COMMON_MAX_SPARES;
        defaultForkJoinWorkerThreadFactory =
                new DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");

        /**
         * 初始化 forkjoin线程池对象 就是 通过 makeCommonPool() 初始化线程池对象
         */
        common = java.security.AccessController.doPrivileged
                (new java.security.PrivilegedAction<ForkJoinPool>() {
                    public ForkJoinPool run() {
                        return makeCommonPool();
                    }
                });
        int par = common.config & SMASK; // report 1 even if threads disabled
        commonParallelism = par > 0 ? par : 1;
    }

    /**
     * Creates and returns the common pool, respecting user settings
     * specified via system properties.
     * 初始化 全局的 forkjoin线程池对象
     */
    private static ForkJoinPool makeCommonPool() {
        // 并行度 默认为-1
        int parallelism = -1;
        ForkJoinWorkerThreadFactory factory = null;
        UncaughtExceptionHandler handler = null;
        try {  // ignore exceptions in accessing/parsing properties
            // 尝试从系统变量中 获取相关参数
            String pp = System.getProperty
                    ("java.util.concurrent.ForkJoinPool.common.parallelism");
            String fp = System.getProperty
                    ("java.util.concurrent.ForkJoinPool.common.threadFactory");
            String hp = System.getProperty
                    ("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            if (pp != null)
                parallelism = Integer.parseInt(pp);
            if (fp != null)
                factory = ((ForkJoinWorkerThreadFactory) ClassLoader.
                        getSystemClassLoader().loadClass(fp).newInstance());
            if (hp != null)
                handler = ((UncaughtExceptionHandler) ClassLoader.
                        getSystemClassLoader().loadClass(hp).newInstance());
        } catch (Exception ignore) {
        }
        if (factory == null) {
            if (System.getSecurityManager() == null)
                // 使用默认的线程池工厂
                factory = defaultForkJoinWorkerThreadFactory;
            else // use security-managed default
                // 如果需要权限校验 生成能创建 InnocuousForkJoinWorkerThread 的线程工厂
                factory = new InnocuousForkJoinWorkerThreadFactory();
        }
        // 默认情况下 parallelism = Runtime.getRuntime().availableProcessors() - 1
        if (parallelism < 0 && // default 1 less than #cores
                (parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0)
            parallelism = 1;
        // 并行度 不能超过 MAX_CAP
        if (parallelism > MAX_CAP)
            parallelism = MAX_CAP;
        // 构建 forkjoin 线程池
        return new ForkJoinPool(parallelism, factory, handler, LIFO_QUEUE,
                "ForkJoinPool.commonPool-worker-");
    }

    /**
     * Factory for innocuous worker threads
     * 无害的线程池 ???
     */
    static final class InnocuousForkJoinWorkerThreadFactory
            implements ForkJoinWorkerThreadFactory {

        /**
         * An ACC to restrict permissions for the factory itself.
         * The constructed workers have no permissions set.
         * 权限先不看
         */
        private static final AccessControlContext innocuousAcc;

        static {
            Permissions innocuousPerms = new Permissions();
            innocuousPerms.add(modifyThreadPermission);
            innocuousPerms.add(new RuntimePermission(
                    "enableContextClassLoaderOverride"));
            innocuousPerms.add(new RuntimePermission(
                    "modifyThreadGroup"));
            innocuousAcc = new AccessControlContext(new ProtectionDomain[]{
                    new ProtectionDomain(null, innocuousPerms)
            });
        }

        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return (ForkJoinWorkerThread.InnocuousForkJoinWorkerThread)
                    java.security.AccessController.doPrivileged(
                            new java.security.PrivilegedAction<ForkJoinWorkerThread>() {
                                public ForkJoinWorkerThread run() {
                                    return new ForkJoinWorkerThread.
                                            InnocuousForkJoinWorkerThread(pool);
                                }
                            }, innocuousAcc);
        }
    }

}
