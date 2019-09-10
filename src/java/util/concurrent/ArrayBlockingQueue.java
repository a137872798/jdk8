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

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.lang.ref.WeakReference;
import java.util.Spliterators;
import java.util.Spliterator;

/**
 * A bounded {@linkplain BlockingQueue blocking queue} backed by an
 * array.  This queue orders elements FIFO (first-in-first-out).  The
 * <em>head</em> of the queue is that element that has been on the
 * queue the longest time.  The <em>tail</em> of the queue is that
 * element that has been on the queue the shortest time. New elements
 * are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue.
 *
 * <p>This is a classic &quot;bounded buffer&quot;, in which a
 * fixed-sized array holds elements inserted by producers and
 * extracted by consumers.  Once created, the capacity cannot be
 * changed.  Attempts to {@code put} an element into a full queue
 * will result in the operation blocking; attempts to {@code take} an
 * element from an empty queue will similarly block.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order. Fairness
 * generally decreases throughput but reduces variability and avoids
 * starvation.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @param <E> the type of elements held in this collection
 *            基于数组的 阻塞队列
 * @author Doug Lea
 * @since 1.5
 */
public class ArrayBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {

    /**
     * Serialization ID. This class relies on default serialization
     * even for the items array, which is default-serialized, even if
     * it is empty. Otherwise it could not be declared final, which is
     * necessary here.
     */
    private static final long serialVersionUID = -817911632652898426L;

    /**
     * The queued items
     */
    final Object[] items;

    /** items index for next take, poll, peek or remove */
    /**
     * 下次拉取的下标  不需要用 volatile 修饰 因为该对象始终在锁内执行
     */
    int takeIndex;

    /** items index for next put, offer, or add */
    /**
     * 记录下次要存放的下标
     */
    int putIndex;

    /** Number of elements in the queue */
    /**
     * 队列中总数
     */
    int count;

    /*
     * Concurrency control uses the classic two-condition algorithm
     * found in any textbook.
     */

    /**
     * Main lock guarding all access
     */
    final ReentrantLock lock;

    // 下面2个 condition 对象 都是从lock 中生成  注意 不同线程在 调用 notEmpty.await 时会将 自身 做为一个node 保存到 condition 中
    // 对应的condition 队列应该是这样子   0 ---- > 0 ------> 0 ----->
    //                                  thread1   thread2   thread3     每个节点代表被阻塞的线程  signAll 就代表让这些线程全部回到同步队列中进行竞争

    /**
     * Condition for waiting takes
     */
    private final Condition notEmpty;

    /**
     * Condition for waiting puts
     */
    private final Condition notFull;

    /**
     * Shared state for currently active iterators, or null if there
     * are known not to be any.  Allows queue operations to update
     * iterator state.
     * 并发的 所有活跃迭代器 构建了 这个 迭代器 组合对象
     */
    transient Itrs itrs = null;

    // Internal helper methods

    /**
     * Circularly decrement i.
     * 在基于 轮的结构中 将 i -1    0 就意味着数组的尾部
     */
    final int dec(int i) {
        return ((i == 0) ? items.length : i) - 1;
    }

    /**
     * Returns item at index i.
     * 传入下标获取元素
     */
    @SuppressWarnings("unchecked")
    final E itemAt(int i) {
        return (E) items[i];
    }

    /**
     * Throws NullPointerException if argument is null.
     *
     * @param v the element
     */
    private static void checkNotNull(Object v) {
        if (v == null)
            throw new NullPointerException();
    }

    /**
     * Inserts element at current put position, advances, and signals.
     * Call only when holding lock.
     * 将元素添加到数组中
     */
    private void enqueue(E x) {
        // assert lock.getHoldCount() == 1;
        // assert items[putIndex] == null;
        final Object[] items = this.items;
        items[putIndex] = x;
        // 当元素 超过数组长度后 putIndex 会指向0的位置
        if (++putIndex == items.length)
            putIndex = 0;
        // 元素数量继续增加
        count++;
        // 唤醒 之前阻塞的线程  一次添加 对应 一次唤醒 如果没有阻塞的 condition signal 无法送 condition队列中移动元素 也没有任何副作用
        notEmpty.signal();
    }

    /**
     * Extracts element at current take position, advances, and signals.
     * Call only when holding lock.
     * 将 元素移除队列
     */
    private E dequeue() {
        // assert lock.getHoldCount() == 1;
        // assert items[takeIndex] != null;
        final Object[] items = this.items;
        @SuppressWarnings("unchecked")
        E x = (E) items[takeIndex];
        items[takeIndex] = null;
        if (++takeIndex == items.length)
            takeIndex = 0;
        count--;
        // 如果当前有某些线程 创建了迭代器 (itr) itrs 就会被初始化 他作为 访问迭代器的入口 elementDequeued 会根据 takeIndex 是否为0 或者 是否数组为空走不同的清理逻辑
        // 这样使用迭代器的线程就会感知到
        if (itrs != null)
            itrs.elementDequeued();
        // 唤醒之前 尝试添加元素被阻塞的线程
        notFull.signal();
        return x;
    }

    /**
     * Deletes item at array index removeIndex.
     * Utility for remove(Object) and iterator.remove.
     * Call only when holding lock.
     * 根据指定的下标删除元素
     */
    void removeAt(final int removeIndex) {
        // assert lock.getHoldCount() == 1;
        // assert items[removeIndex] != null;
        // assert removeIndex >= 0 && removeIndex < items.length;
        final Object[] items = this.items;
        // 如果 移除的元素 刚好就是 takeIndex 直接移除对应的元素 以及移动偏移量就好
        if (removeIndex == takeIndex) {
            // removing front item; just advance
            // 置空元素
            items[takeIndex] = null;
            if (++takeIndex == items.length)
                takeIndex = 0;
            count--;
            if (itrs != null)
                itrs.elementDequeued();
        } else {
            // an "interior" remove

            // slide over all others up through putIndex.
            // 删除 下标对应的元素 并将 其他元素 移动填补空缺  也就是 removeIndex 到 putIndex 之间的元素 全部往前移
            final int putIndex = this.putIndex;
            for (int i = removeIndex; ; ) {
                // 生成下个元素的下标
                int next = i + 1;
                // 代表删除到了末尾 又从头开始删除
                if (next == items.length)
                    next = 0;
                if (next != putIndex) {
                    // 将下个下标对应的元素 移动到上个下标的位置
                    items[i] = items[next];
                    i = next;
                } else {
                    // 代表next == putIndex
                    items[i] = null;
                    // 将 putIndex 前移
                    this.putIndex = i;
                    break;
                }
            }
            count--;
            // 对迭代器做处理
            if (itrs != null)
                itrs.removedAt(removeIndex);
        }
        // 唤醒 代表可以填充元素了
        notFull.signal();
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity and default access policy.
     *
     * @param capacity the capacity of this queue
     * @throws IllegalArgumentException if {@code capacity < 1}
     *                                  初始化对象 并设置公平模式 或非公平模式
     */
    public ArrayBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity and the specified access policy.
     *
     * @param capacity the capacity of this queue
     * @param fair     if {@code true} then queue accesses for threads blocked
     *                 on insertion or removal, are processed in FIFO order;
     *                 if {@code false} the access order is unspecified.
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public ArrayBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0)
            throw new IllegalArgumentException();
        // 初始化数组
        this.items = new Object[capacity];
        lock = new ReentrantLock(fair);
        // 这里构建了2个 condition 对象 每个对象会维护自己的 condition队列
        notEmpty = lock.newCondition();
        notFull = lock.newCondition();
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity, the specified access policy and initially containing the
     * elements of the given collection,
     * added in traversal order of the collection's iterator.
     *
     * @param capacity the capacity of this queue
     * @param fair     if {@code true} then queue accesses for threads blocked
     *                 on insertion or removal, are processed in FIFO order;
     *                 if {@code false} the access order is unspecified.
     * @param c        the collection of elements to initially contain
     * @throws IllegalArgumentException if {@code capacity} is less than
     *                                  {@code c.size()}, or less than 1.
     * @throws NullPointerException     if the specified collection or any
     *                                  of its elements are null
     *                                  使用指定的容器对象进行初始化
     */
    public ArrayBlockingQueue(int capacity, boolean fair,
                              Collection<? extends E> c) {
        this(capacity, fair);

        final ReentrantLock lock = this.lock;
        lock.lock(); // Lock only for visibility, not mutual exclusion
        try {
            int i = 0;
            try {
                for (E e : c) {
                    checkNotNull(e);
                    items[i++] = e;
                }
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw new IllegalArgumentException();
            }
            count = i;
            // putIndex 代表下个要填充的位置 该数组使用轮式算法 一旦填充完数组后又是从0 开始填充
            putIndex = (i == capacity) ? 0 : i;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and throwing an
     * {@code IllegalStateException} if this queue is full.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws IllegalStateException if this queue is full
     * @throws NullPointerException  if the specified element is null
     */
    public boolean add(E e) {
        return super.add(e);
    }

    /**
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and {@code false} if this queue
     * is full.  This method is generally preferable to method {@link #add},
     * which can fail to insert an element only by throwing an exception.
     *
     * @throws NullPointerException if the specified element is null
     *                              将元素填充到数组中
     */
    public boolean offer(E e) {
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 代表容器满了 不允许添加元素  注意这里的 条件不是 putIndex == items.length 而是 count  count 代表数组内一共有多少元素
            // 也就是 putIndex 对应存放的下标 一旦数组本身满了 就无法继续存放 而如果到了数组末尾 为了避免复杂的扩容选择 从 首元素开始重新添加元素
            // 当然前提条件是 首元素已经被拿出来了
            if (count == items.length)
                return false;
            else {
                enqueue(e);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * for space to become available if the queue is full.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     *                              将元素添加到 数组中  这里是阻塞的添加 如果 数组当前已经满了 就会 阻塞线程 直到其他线程 取走某个元素
     */
    public void put(E e) throws InterruptedException {
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length)
                // 等待元素被取走 同时 借助同步队列实现 在从await唤醒时 该线程还是独占锁的
                // 调用await 会让占有锁的该线程 放弃锁 使得同步队列下个元素获取到锁   不过在释放锁的时候 下个获取到锁的线程 会修改 lock.exclusiveOwnerThread
                notFull.await();
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * up to the specified wait time for space to become available if
     * the queue is full.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     *                              在指定时间内 尝试添加元素
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {

        checkNotNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (nanos <= 0)
                    return false;
                // 如果超时被唤醒时 发现 元素还是满的 返回false
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 拉取元素  该方法是非阻塞拉取
     *
     * @return
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (count == 0) ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞拉取元素
     *
     * @return
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0)
                // 获取锁的线程 释放该锁 并进入阻塞状态 等待其他线程唤醒该线程
                notEmpty.await();
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (nanos <= 0)
                    return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查看元素 但是不阻塞 也不移除数组
     *
     * @return
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return itemAt(takeIndex); // null when queue is empty
        } finally {
            lock.unlock();
        }
    }

    // this doc comment is overridden to remove the reference to collections
    // greater in size than Integer.MAX_VALUE

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    // this doc comment is a modified copy of the inherited doc comment,
    // without the reference to unlimited queues.

    /**
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking. This is always equal to the initial capacity of this queue
     * less the current {@code size} of this queue.
     *
     * <p>Note that you <em>cannot</em> always tell if an attempt to insert
     * an element will succeed by inspecting {@code remainingCapacity}
     * because it may be the case that another thread is about to
     * insert or remove an element.
     */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * <p>Removal of interior elements in circular array based queues
     * is an intrinsically slow and disruptive operation, so should
     * be undertaken only in exceptional circumstances, ideally
     * only when the queue is known not to be accessible by other
     * threads.
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     * 移除某个元素
     */
    public boolean remove(Object o) {
        if (o == null) return false;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    if (o.equals(items[i])) {
                        // 该方法 又会自动维护 元素的位置 也就是 将 putIndex 前的元素前移
                        removeAt(i);
                        return true;
                    }
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    if (o.equals(items[i]))
                        return true;
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        Object[] a;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            a = new Object[count];
            int n = items.length - takeIndex;
            if (count <= n)
                System.arraycopy(items, takeIndex, a, 0, count);
            else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
        } finally {
            lock.unlock();
        }
        return a;
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence; the runtime type of the returned array is that of
     * the specified array.  If the queue fits in the specified array, it
     * is returned therein.  Otherwise, a new array is allocated with the
     * runtime type of the specified array and the size of this queue.
     *
     * <p>If this queue fits in the specified array with room to spare
     * (i.e., the array has more elements than this queue), the element in
     * the array immediately following the end of the queue is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>Suppose {@code x} is a queue known to contain only strings.
     * The following code can be used to dump the queue into a newly
     * allocated array of {@code String}:
     *
     * <pre> {@code String[] y = x.toArray(new String[0]);}</pre>
     * <p>
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException  if the runtime type of the specified array
     *                              is not a supertype of the runtime type of every element in
     *                              this queue
     * @throws NullPointerException if the specified array is null
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            final int len = a.length;
            if (len < count)
                a = (T[]) java.lang.reflect.Array.newInstance(
                        a.getClass().getComponentType(), count);
            int n = items.length - takeIndex;
            if (count <= n)
                System.arraycopy(items, takeIndex, a, 0, count);
            else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
            if (len > count)
                a[count] = null;
        } finally {
            lock.unlock();
        }
        return a;
    }

    public String toString() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k == 0)
                return "[]";

            final Object[] items = this.items;
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = takeIndex; ; ) {
                Object e = items[i];
                sb.append(e == this ? "(this Collection)" : e);
                if (--k == 0)
                    return sb.append(']').toString();
                sb.append(',').append(' ');
                if (++i == items.length)
                    i = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     */
    public void clear() {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    // 将 putIndex 与 takeIndex 之间的元素全部清除
                    items[i] = null;
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);
                takeIndex = putIndex;
                count = 0;
                if (itrs != null)
                    itrs.queueIsEmpty();
                // 等价于 signalAll
                for (; k > 0 && lock.hasWaiters(notFull); k--)
                    notFull.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     *                                       将内部所有可用元素 移动到 c 中
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        checkNotNull(c);
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 设置合适的数量上限
            int n = Math.min(maxElements, count);
            // 拉取元素的下标
            int take = takeIndex;
            int i = 0;
            try {
                while (i < n) {
                    @SuppressWarnings("unchecked")
                    // 从数组中拉取元素并添加到 c中
                            E x = (E) items[take];
                    c.add(x);
                    items[take] = null;
                    if (++take == items.length)
                        take = 0;
                    i++;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                // i > 0 代表 有元素已经添加到 c中了
                if (i > 0) {
                    count -= i;
                    // 更新 take 指针
                    takeIndex = take;
                    // 修改迭代器相关逻辑
                    if (itrs != null) {
                        if (count == 0)
                            itrs.queueIsEmpty();
                        else if (i > take)
                            itrs.takeIndexWrapped();
                    }
                    // 为什么不使用 signalAll 就是因为这样是可控的 随时根据需要指定唤醒次数
                    for (; i > 0 && lock.hasWaiters(notFull); i--)
                        notFull.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     * 获取迭代器对象
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * Shared data between iterators and their queue, allowing queue
     * modifications to update iterators when elements are removed.
     * <p>
     * This adds a lot of complexity for the sake of correctly
     * handling some uncommon operations, but the combination of
     * circular-arrays and supporting interior removes (i.e., those
     * not at head) would cause iterators to sometimes lose their
     * places and/or (re)report elements they shouldn't.  To avoid
     * this, when a queue has one or more iterators, it keeps iterator
     * state consistent by:
     * <p>
     * (1) keeping track of the number of "cycles", that is, the
     * number of times takeIndex has wrapped around to 0.
     * (2) notifying all iterators via the callback removedAt whenever
     * an interior element is removed (and thus other elements may
     * be shifted).
     * <p>
     * These suffice to eliminate iterator inconsistencies, but
     * unfortunately add the secondary responsibility of maintaining
     * the list of iterators.  We track all active iterators in a
     * simple linked list (accessed only when the queue's lock is
     * held) of weak references to Itr.  The list is cleaned up using
     * 3 different mechanisms:
     * <p>
     * (1) Whenever a new iterator is created, do some O(1) checking for
     * stale list elements.
     * <p>
     * (2) Whenever takeIndex wraps around to 0, check for iterators
     * that have been unused for more than one wrap-around cycle.
     * <p>
     * (3) Whenever the queue becomes empty, all iterators are notified
     * and this entire data structure is discarded.
     * <p>
     * So in addition to the removedAt callback that is necessary for
     * correctness, iterators have the shutdown and takeIndexWrapped
     * callbacks that help remove stale iterators from the list.
     * <p>
     * Whenever a list element is examined, it is expunged if either
     * the GC has determined that the iterator is discarded, or if the
     * iterator reports that it is "detached" (does not need any
     * further state updates).  Overhead is maximal when takeIndex
     * never advances, iterators are discarded before they are
     * exhausted, and all removals are interior removes, in which case
     * all stale iterators are discovered by the GC.  But even in this
     * case we don't increase the amortized complexity.
     * <p>
     * Care must be taken to keep list sweeping methods from
     * reentrantly invoking another such method, causing subtle
     * corruption bugs.
     * 维护了 并发访问 阻塞队列的 所有迭代器
     */
    class Itrs {

        /**
         * Node in a linked list of weak iterator references.
         * 每个添加的 迭代器 通过包装成 node 节点 相互关联  迭代器本身是可以被回收的 而node 节点是强引用的
         * 这里的 itr 使用弱引用是因为  不需要 itrs 一直保持对 itr 的控制 而用户创建itr 本身是强引用 也就是用户本身获取的迭代器是不会被回收的
         */
        private class Node extends WeakReference<Itr> {
            Node next;

            Node(Itr iterator, Node next) {
                // iter 可被回收
                super(iterator);
                this.next = next;
            }
        }

        /**
         * Incremented whenever takeIndex wraps around to 0
         */
        // 代表takeIndex 重新回到0 的次数
        int cycles = 0;

        /** Linked list of weak iterator references */
        /**
         * 首节点
         */
        private Node head;

        /** Used to expunge stale iterators */
        /**
         * 用于删除 不新鲜的 迭代器
         */
        private Node sweeper = null;

        // 清理探针
        private static final int SHORT_SWEEP_PROBES = 4;
        private static final int LONG_SWEEP_PROBES = 16;

        /**
         * 使用一个 迭代器对象进行初始化 初始化本身是包含在锁内的
         *
         * @param initial
         */
        Itrs(Itr initial) {
            register(initial);
        }

        /**
         * Sweeps itrs, looking for and expunging stale iterators.
         * If at least one was found, tries harder to find more.
         * Called only from iterating thread.
         *
         * @param tryHarder whether to start in try-harder mode, because
         *                  there is known to be at least one iterator to collect
         *                  每次创建新的 迭代器对象时 都会执行清理工作 并且传入的 tryHarder 为false
         */
        void doSomeSweeping(boolean tryHarder) {
            // assert lock.getHoldCount() == 1;
            // assert head != null;
            // 代表使用短探针
            int probes = tryHarder ? LONG_SWEEP_PROBES : SHORT_SWEEP_PROBES;
            Node o, p;
            // 清扫用的节点
            final Node sweeper = this.sweeper;
            boolean passedGo;   // to limit search to one full sweep

            // 首次进入上面的分支
            if (sweeper == null) {
                o = null;
                // p 置为head
                p = head;
                // 代表进行的是全面扫描
                passedGo = true;
            } else {
                // o置为扫地节点
                o = sweeper;
                // p 为扫地节点的下个节点
                p = o.next;
                // 代表不用全面扫描
                passedGo = false;
            }

            // 根据 探针大小 开始探测是否有无效的 迭代器需要清理
            for (; probes > 0; probes--) {
                // p 每次都会更新成 next节点 一旦发现为 null 代表不需要再进行清理了
                if (p == null) {
                    // 如果是 从head 开始扫的 退出循环
                    if (passedGo)
                        break;
                    // 非全面扫描 修改为全面扫描
                    o = null;
                    p = head;
                    passedGo = true;
                }
                // 获取迭代器对象 it 可能已经为null 了
                final Itr it = p.get();
                // 获取下个节点 也就是更早的节点
                final Node next = p.next;
                // 代表被 GC回收了  如果 isDetached 代表初始化迭代器 时本身就是无效的 也会被移除
                // isDetached 可以看作是 itr 不需要继续被 itrs 维护时采取的一种措施
                if (it == null || it.isDetached()) {
                    // found a discarded/exhausted iterator
                    // 一旦发现一个 被丢弃的迭代器 延长探针
                    probes = LONG_SWEEP_PROBES; // "try harder"
                    // unlink p
                    // 将该节点清理 因为可能是 it.isDetached() 才进入这里的
                    p.clear();
                    // 帮助GC 回收
                    p.next = null;
                    // o 代表上个节点 如果 本次探测的节点就是 head节点 那么 o 就是null 这样要将下个节点作为head
                    if (o == null) {
                        head = next;
                        if (next == null) {
                            // We've run out of iterators to track; retire
                            // 迭代器都无效了 清除
                            itrs = null;
                            return;
                        }
                    } else
                        // 该操作相当于 将 p节点从链表中移除
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }

            // 将 本次的结果 作为下次的起点
            this.sweeper = (p == null) ? null : o;
        }

        /**
         * Adds a new iterator to the linked list of tracked iterators.
         * 将新的 itr 作为首节点 之后的节点连接到它上面
         */
        void register(Itr itr) {
            // assert lock.getHoldCount() == 1;
            head = new Node(itr, head);
        }

        /**
         * Called whenever takeIndex wraps around to 0.
         * <p>
         * Notifies all iterators, and expunges any that are now stale.
         * 每当 takeIndex 回到0 时调用
         */
        void takeIndexWrapped() {
            // assert lock.getHoldCount() == 1;
            // 增加循环次数
            cycles++;
            for (Node o = null, p = head; p != null; ) {
                // 获取维护的迭代器链表
                final Itr it = p.get();
                // 获取下个节点
                final Node next = p.next;
                // 如果迭代器已经被回收 或者 迭代器自身判定不需要被维护了
                if (it == null || it.takeIndexWrapped()) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    // 清除迭代器
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }
            // 没有元素时 清空迭代器
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * Called whenever an interior remove (not at takeIndex) occurred.
         * <p>
         * Notifies all iterators, and expunges any that are now stale.
         * 移除非 takeIndex 对应的元素时触发
         */
        void removedAt(int removedIndex) {
            // 从头节点开始遍历每个迭代器 一旦 对应的子迭代器满足触发条件就停止对迭代器的维护 可以理解为 该迭代器本身已经失效了 无法调用 next 方法
            for (Node o = null, p = head; p != null; ) {
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.removedAt(removedIndex)) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * Called whenever the queue becomes empty.
         * <p>
         * Notifies all active iterators that the queue is empty,
         * clears all weak refs, and unlinks the itrs datastructure.
         * 当数组置空时触发
         */
        void queueIsEmpty() {
            // assert lock.getHoldCount() == 1;
            for (Node p = head; p != null; p = p.next) {
                Itr it = p.get();
                // 迭代器还存在的情况做清理
                if (it != null) {
                    p.clear();
                    // shutdown 会将各个属性都设置成无效
                    it.shutdown();
                }
            }
            head = null;
            itrs = null;
        }

        /**
         * Called whenever an element has been dequeued (at takeIndex).
         * 当元素出队列时触发  对应的行为为 takeIndex 的元素被移除且 ++takeIndex
         */
        void elementDequeued() {
            // assert lock.getHoldCount() == 1;
            // 代表此时 数组已经没有元素了
            if (count == 0)
                queueIsEmpty();
            // 代表刚好 经过了一轮
            else if (takeIndex == 0)
                takeIndexWrapped();
        }
    }

    /**
     * Iterator for ArrayBlockingQueue.
     * <p>
     * To maintain weak consistency with respect to puts and takes, we
     * read ahead one slot, so as to not report hasNext true but then
     * not have an element to return.
     * <p>
     * We switch into "detached" mode (allowing prompt unlinking from
     * itrs without help from the GC) when all indices are negative, or
     * when hasNext returns false for the first time.  This allows the
     * iterator to track concurrent updates completely accurately,
     * except for the corner case of the user calling Iterator.remove()
     * after hasNext() returned false.  Even in this case, we ensure
     * that we don't remove the wrong element by keeping track of the
     * expected element to remove, in lastItem.  Yes, we may fail to
     * remove lastItem from the queue if it moved due to an interleaved
     * interior remove while in detached mode.
     * 阻塞队列 的 迭代器对象   迭代器的大小 应该是多少  如果当前takeIndex 不为0 那么 迭代器的终点应该是下一轮的某个偏移量 而不是本轮的末尾
     * 因为 如果不足1轮 那可以遍历的总元素就小于数组大小 这是不合理的  所以才需要记录 cycle 这个字段
     */
    private class Itr implements Iterator<E> {
        /** Index to look for new nextItem; NONE at end */
        /**
         * 当前指针
         */
        private int cursor;

        /** Element to be returned by next call to next(); null if none */
        /**
         * 调用next() 时 返回的结果
         */
        private E nextItem;

        /** Index of nextItem; NONE if none, REMOVED if removed elsewhere */
        /**
         * 下个元素的指针
         */
        private int nextIndex;

        /** Last element returned; null if none or not detached. */
        /**
         * 返回最后一个元素
         */
        private E lastItem;

        /** Index of lastItem, NONE if none, REMOVED if removed elsewhere */
        /**
         * 最后元素的指针
         */
        private int lastRet;

        /** Previous value of takeIndex, or DETACHED when detached */
        /**
         * takeIndex 的前一个
         */
        private int prevTakeIndex;

        /** Previous value of iters.cycles */
        /**
         * itrs 的 上个 cycles
         */
        private int prevCycles;

        /**
         * Special index value indicating "not available" or "undefined"
         */
        private static final int NONE = -1;

        /**
         * Special index value indicating "removed elsewhere", that is,
         * removed by some operation other than a call to this.remove().
         */
        private static final int REMOVED = -2;

        /**
         * Special value for prevTakeIndex indicating "detached mode"
         */
        private static final int DETACHED = -3;

        Itr() {
            // assert lock.getHoldCount() == 0;
            // 最后一个引用默认为null
            lastRet = NONE;
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            // 加锁
            lock.lock();
            try {
                // 迭代的 初始化时会获取外部 数组的 元素数量
                if (count == 0) {
                    // assert itrs == null;
                    cursor = NONE;
                    nextIndex = NONE;
                    prevTakeIndex = DETACHED;
                } else {
                    // 代表 拉取元素的 偏移量
                    final int takeIndex = ArrayBlockingQueue.this.takeIndex;
                    // prevTakeIndex 应该是 上次拉取的下标 初始化是 跟 takeIndex 指向一个元素
                    prevTakeIndex = takeIndex;
                    // 一开始 nextIndex 跟 takeIndex 指向一个元素 代表下个可以拉取的元素
                    // 在刚初始化 迭代器对象时 调用 hasNext 就是判断 nextItem 是否存在 也就是 takeIndex 对应的元素 是否存在
                    // hasNext 本身 是存在并发问题的 可能现在判断有元素 而下一刻 元素已经不在了
                    nextItem = itemAt(nextIndex = takeIndex);
                    // 光标处在 takeIndex 与 putIndex 之间  默认为 takeIndex + 1
                    cursor = incCursor(takeIndex);
                    // 初始化 并发情况的迭代器
                    if (itrs == null) {
                        itrs = new Itrs(this);
                    } else {
                        // 如果已经初始化 将 迭代器注册到上面  且新注册的 作为首节点
                        itrs.register(this); // in this order
                        // 做一些清理工作
                        itrs.doSomeSweeping(false);
                    }
                    // 获取总迭代器的 循环次数 也就是 takeIndex 到0 的次数
                    prevCycles = itrs.cycles;
                    // assert takeIndex >= 0;
                    // assert prevTakeIndex == takeIndex;
                    // assert nextIndex >= 0;
                    // assert nextItem != null;
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * 如果初始化 迭代器时 本身数组就没有元素 会设置为 -3
         *
         * @return
         */
        boolean isDetached() {
            // assert lock.getHoldCount() == 1;
            return prevTakeIndex < 0;
        }

        /**
         * 这里是不是可以理解为 不断的 put 在迭代器调用next() 后 就会更新光标能够获取到指向元素的 下标
         * @param index
         * @return
         */
        private int incCursor(int index) {
            // assert lock.getHoldCount() == 1;
            // 增加 index 的值 并且 如果是 items.length  重置成0
                if (++index == items.length)
                index = 0;
            // 应该是 意味着 没有元素可拉了 光标就为NONE 吧
            if (index == putIndex)
                index = NONE;
            return index;
        }

        /**
         * Returns true if index is invalidated by the given number of
         * dequeues, starting from prevTakeIndex.
         * 判断是否无效
         * @param prevTakeIndex 代表上次 takeIndex 的指针停留位置
         * @param dequeues 代表距离上次 已经被取走了多少元素 可能包含多次循环 也就是 值会超过 数组的长度
         * @param length 代表数组的长度
         */
        private boolean invalidated(int index, int prevTakeIndex,
                                    long dequeues, int length) {
            // 传入下标 小于0 代表异常
            if (index < 0)
                return false;
            // 代表 本次拉取 距离上次 拉取的距离
            int distance = index - prevTakeIndex;
            // 小于0 代表已经绕过一圈了
            if (distance < 0)
                distance += length;
            return dequeues > distance;
        }

        /**
         * Adjusts indices to incorporate all dequeues since the last
         * operation on this iterator.  Call only from iterating thread.
         * 从 队列中合并元素  该方法在锁内执行
         */
        private void incorporateDequeues() {
            // assert lock.getHoldCount() == 1;
            // assert itrs != null;
            // assert !isDetached();
            // assert count > 0;

            // 获取迭代器的 循环次数
            final int cycles = itrs.cycles;
            // 获取拉取元素的下标
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            // 获取上次 迭代器 循环次数
            final int prevCycles = this.prevCycles;
            // 获取上一次拉取的指针
            final int prevTakeIndex = this.prevTakeIndex;

            // 判断指针距离上次是否发生了变化 有的话 就代表 减少了 元素
            if (cycles != prevCycles || takeIndex != prevTakeIndex) {
                // 获取数组长度
                final int len = items.length;
                // how far takeIndex has advanced since the previous
                // operation of this iterator
                // 代表 在 初始化到 调用 hasNext 之间 被拉取了多少元素
                long dequeues = (cycles - prevCycles) * len
                        + (takeIndex - prevTakeIndex);

                // Check indices for invalidation
                // 初始化时  lastRet 为 NONE  这里好像是判断有些指针是否无效了
                if (invalidated(lastRet, prevTakeIndex, dequeues, len))
                    lastRet = REMOVED;
                // 如果 nextIndex 距离上次拉取的距离 小于 迭代器被拉取的元素数量 代表 nextIndex已经无效了
                if (invalidated(nextIndex, prevTakeIndex, dequeues, len))
                    nextIndex = REMOVED;
                // 这里是什么意思
                if (invalidated(cursor, prevTakeIndex, dequeues, len))
                    cursor = takeIndex;

                // 偏差过大 迭代器需要被移除
                if (cursor < 0 && nextIndex < 0 && lastRet < 0)
                    detach();
                else {
                    // 更新指针 用于下次判断 偏移量
                    this.prevCycles = cycles;
                    this.prevTakeIndex = takeIndex;
                }
            }
        }

        /**
         * Called when itrs should stop tracking this iterator, either
         * because there are no more indices to update (cursor < 0 &&
         * nextIndex < 0 && lastRet < 0) or as a special exception, when
         * lastRet >= 0, because hasNext() is about to return false for the
         * first time.  Call only from iterating thread.
         * 迭代器doSomeSweeping 会将 prevTake <0 的迭代器移除
         */
        private void detach() {
            // Switch to detached mode
            // assert lock.getHoldCount() == 1;
            // assert cursor == NONE;
            // assert nextIndex < 0;
            // assert lastRet < 0 || nextItem == null;
            // assert lastRet < 0 ^ lastItem != null;
            if (prevTakeIndex >= 0) {
                // assert itrs != null;
                prevTakeIndex = DETACHED;
                // try to unlink from itrs (but not too hard)
                itrs.doSomeSweeping(true);
            }
        }

        /**
         * For performance reasons, we would like not to acquire a lock in
         * hasNext in the common case.  To allow for this, we only access
         * fields (i.e. nextItem) that are not modified by update operations
         * triggered by queue modifications.
         * 判断是否有下个 元素
         * 注意 这里没有加锁 也就是这里是允许 判断出现误判的
         */
        public boolean hasNext() {
            // assert lock.getHoldCount() == 0;
            if (nextItem != null)
                return true;
            // 当前 nextItem 为null 可能就需要判断是否真的没有元素 因为可能其他线程已经添加了新元素
            noNext();
            return false;
        }

        private void noNext() {
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                // assert cursor == NONE;
                // assert nextIndex == NONE;
                // 判断是否分离  就是 predTakeIndex 是否小于 0  这种应该是某种特殊标识
                if (!isDetached()) {
                    // assert lastRet >= 0;
                    // 查看 迭代器距离上次调用是否发生了变化 并清理失效的迭代器
                    incorporateDequeues(); // might update lastRet
                    // 如果lastRet 还有效 首次 lastRet小于0
                    if (lastRet >= 0) {
                        // 更新最后一个元素
                        lastItem = itemAt(lastRet);
                        // assert lastItem != null;
                        // 这里为什么要丢弃
                        detach();
                    }
                }
                // assert isDetached();
                // assert lastRet < 0 ^ lastItem != null;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取下个元素
         * @return
         */
        public E next() {
            // assert lock.getHoldCount() == 0;
            // 在初始化时 该对象已经确定了
            final E x = nextItem;
            if (x == null)
                throw new NoSuchElementException();
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                // 如果该迭代器是有效的
                if (!isDetached())
                    // 检查下标是否异常 异常情况 清除掉该迭代器对象
                    incorporateDequeues();
                // assert nextIndex != NONE;
                // assert lastItem == null;
                // lastRet 记录了 上次返回的结果下标
                lastRet = nextIndex;
                // 光标本身就是 nextIndex + 1
                final int cursor = this.cursor;
                if (cursor >= 0) {
                    // 更新下标 并且下次 hasNext 也能正常返回
                    nextItem = itemAt(nextIndex = cursor);
                    // assert nextItem != null;
                    // 更新光标
                    this.cursor = incCursor(cursor);
                } else {
                    nextIndex = NONE;
                    nextItem = null;
                }
            } finally {
                lock.unlock();
            }
            return x;
        }

        /**
         * 每当调用一次 next 后可以调用一次 remove 就是将之前 next返回的元素从迭代器中移除
         */
        public void remove() {
            // assert lock.getHoldCount() == 0;
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached())
                    // 检测下标是否还有效
                    incorporateDequeues(); // might update lastRet or detach
                // lastRet 代表上次指向的下标
                final int lastRet = this.lastRet;
                // 置空  避免连续2次调用 remove
                this.lastRet = NONE;
                // 如果上次指向的下标有效
                if (lastRet >= 0) {
                    // 确保迭代器本身有效
                    if (!isDetached())
                        // 移除掉对应的元素
                        removeAt(lastRet);
                    else {
                        // 迭代器本身无效的话
                        final E lastItem = this.lastItem;
                        // assert lastItem != null;
                        this.lastItem = null;
                        // 允许删除 lastItem
                        if (itemAt(lastRet) == lastItem)
                            removeAt(lastRet);
                    }
                // 针对已经被置空的情况 再次调用抛出异常
                } else if (lastRet == NONE)
                    throw new IllegalStateException();
                // else lastRet == REMOVED and the last returned element was
                // previously asynchronously removed via an operation other
                // than this.remove(), so nothing to do.

                // 出现异常 将本迭代器移除
                if (cursor < 0 && nextIndex < 0)
                    detach();
            } finally {
                lock.unlock();
                // assert lastRet == NONE;
                // assert lastItem == null;
            }
        }

        /**
         * Called to notify the iterator that the queue is empty, or that it
         * has fallen hopelessly behind, so that it should abandon any
         * further iteration, except possibly to return one more element
         * from next(), as promised by returning true from hasNext().
         * 当数组本身被清空  通过itrs 触发  因为指针现在都变成无效的 所以之后调用 相关方法 就会提示需要移除该迭代器
         */
        void shutdown() {
            // assert lock.getHoldCount() == 1;
            // 置空各种元素
            cursor = NONE;
            if (nextIndex >= 0)
                nextIndex = REMOVED;
            if (lastRet >= 0) {
                lastRet = REMOVED;
                lastItem = null;
            }
            // 设置为 DETACHED 时  detach() 方法就不会 触发 itrs.sweep 了
            prevTakeIndex = DETACHED;
            // Don't set nextItem to null because we must continue to be
            // able to return it on next().
            //
            // Caller will unlink from itrs when convenient.
        }

        /**
         * 获取距离
         * @param index
         * @param prevTakeIndex
         * @param length
         * @return
         */
        private int distance(int index, int prevTakeIndex, int length) {
            int distance = index - prevTakeIndex;
            if (distance < 0)
                distance += length;
            return distance;
        }

        /**
         * Called whenever an interior remove (not at takeIndex) occurred.
         *
         * @return true if this iterator should be unlinked from itrs
         * 到数组中某个元素被移除且 移除的下标不是 takeIndex 时触发
         */
        boolean removedAt(int removedIndex) {
            // assert lock.getHoldCount() == 1;
            // 迭代器本身已经无效的情况  直接返回成功
            if (isDetached())
                return true;

            // 获取总迭代次数
            final int cycles = itrs.cycles;
            // 获取当前 takeIndex 的下标
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            // 获取 上次 调用 更新的 下标
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;
            final int len = items.length;
            int cycleDiff = cycles - prevCycles;
            // 可以这样判断的原因是 每次到新的一轮 都会触发 takeIndexWrapped 所以可以放心的 调用 cycleDiff++ 而不怕错过了一轮 同时进入第二轮  takeIndexWrapped 会将该迭代器设置为无效
            if (removedIndex < takeIndex)
                cycleDiff++;
            // 代表距离上次检测过了多少距离
            final int removedDistance =
                    (cycleDiff * len) + (removedIndex - prevTakeIndex);
            // assert removedDistance >= 0;
            // 获取当前光标
            int cursor = this.cursor;
            if (cursor >= 0) {
                // 获取 上次拉取元素 到本次的 距离
                int x = distance(cursor, prevTakeIndex, len);
                if (x == removedDistance) {
                    if (cursor == putIndex)
                        this.cursor = cursor = NONE;
                } else if (x > removedDistance) {
                    // assert cursor != prevTakeIndex;
                    this.cursor = cursor = dec(cursor);
                }
            }
            int lastRet = this.lastRet;
            if (lastRet >= 0) {
                int x = distance(lastRet, prevTakeIndex, len);
                if (x == removedDistance)
                    this.lastRet = lastRet = REMOVED;
                else if (x > removedDistance)
                    this.lastRet = lastRet = dec(lastRet);
            }
            int nextIndex = this.nextIndex;
            if (nextIndex >= 0) {
                int x = distance(nextIndex, prevTakeIndex, len);
                if (x == removedDistance)
                    this.nextIndex = nextIndex = REMOVED;
                else if (x > removedDistance)
                    this.nextIndex = nextIndex = dec(nextIndex);
            } else if (cursor < 0 && nextIndex < 0 && lastRet < 0) {
                this.prevTakeIndex = DETACHED;
                return true;
            }
            return false;
        }

        /**
         * Called whenever takeIndex wraps around to zero.
         *
         * @return true if this iterator should be unlinked from itrs
         * 当 takeIndex 变成0 时 触发
         */
        boolean takeIndexWrapped() {
            // assert lock.getHoldCount() == 1;
            // 如果被废弃了 返回true 这样 itrs 就不再维护该迭代器
            if (isDetached())
                return true;
            // 注意这里是 允许 差1轮的  为什么???
            if (itrs.cycles - prevCycles > 1) {
                // All the elements that existed at the time of the last
                // operation are gone, so abandon further iteration.
                shutdown();
                return true;
            }
            return false;
        }

//         /** Uncomment for debugging. */
//         public String toString() {
//             return ("cursor=" + cursor + " " +
//                     "nextIndex=" + nextIndex + " " +
//                     "lastRet=" + lastRet + " " +
//                     "nextItem=" + nextItem + " " +
//                     "lastItem=" + lastItem + " " +
//                     "prevCycles=" + prevCycles + " " +
//                     "prevTakeIndex=" + prevTakeIndex + " " +
//                     "size()=" + size() + " " +
//                     "remainingCapacity()=" + remainingCapacity());
//         }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#ORDERED}, and {@link Spliterator#NONNULL}.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @implNote The {@code Spliterator} implements {@code trySplit} to permit limited
     * parallelism.
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.spliterator
                (this, Spliterator.ORDERED | Spliterator.NONNULL |
                        Spliterator.CONCURRENT);
    }

}
