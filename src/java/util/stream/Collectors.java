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

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.Iterator;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Implementations of {@link Collector} that implement various useful reduction
 * operations, such as accumulating elements into collections, summarizing
 * elements according to various criteria, etc.
 *
 * <p>The following are examples of using the predefined collectors to perform
 * common mutable reduction tasks:
 *
 * <pre>{@code
 *     // Accumulate names into a List
 *     List<String> list = people.stream().map(Person::getName).collect(Collectors.toList());
 *
 *     // Accumulate names into a TreeSet
 *     Set<String> set = people.stream().map(Person::getName).collect(Collectors.toCollection(TreeSet::new));
 *
 *     // Convert elements to strings and concatenate them, separated by commas
 *     String joined = things.stream()
 *                           .map(Object::toString)
 *                           .collect(Collectors.joining(", "));
 *
 *     // Compute sum of salaries of employee
 *     int total = employees.stream()
 *                          .collect(Collectors.summingInt(Employee::getSalary)));
 *
 *     // Group employees by department
 *     Map<Department, List<Employee>> byDept
 *         = employees.stream()
 *                    .collect(Collectors.groupingBy(Employee::getDepartment));
 *
 *     // Compute sum of salaries by department
 *     Map<Department, Integer> totalByDept
 *         = employees.stream()
 *                    .collect(Collectors.groupingBy(Employee::getDepartment,
 *                                                   Collectors.summingInt(Employee::getSalary)));
 *
 *     // Partition students into passing and failing
 *     Map<Boolean, List<Student>> passingFailing =
 *         students.stream()
 *                 .collect(Collectors.partitioningBy(s -> s.getGrade() >= PASS_THRESHOLD));
 *
 * }</pre>
 *
 * @since 1.8
 * Collector的 工具类 内部维护了一些 Collector 的实现类
 */
public final class Collectors {

    static final Set<Collector.Characteristics> CH_CONCURRENT_ID
            = Collections.unmodifiableSet(EnumSet.of(Collector.Characteristics.CONCURRENT,
                                                     Collector.Characteristics.UNORDERED,
                                                     Collector.Characteristics.IDENTITY_FINISH));
    static final Set<Collector.Characteristics> CH_CONCURRENT_NOID
            = Collections.unmodifiableSet(EnumSet.of(Collector.Characteristics.CONCURRENT,
                                                     Collector.Characteristics.UNORDERED));
    static final Set<Collector.Characteristics> CH_ID
            = Collections.unmodifiableSet(EnumSet.of(Collector.Characteristics.IDENTITY_FINISH));
    static final Set<Collector.Characteristics> CH_UNORDERED_ID
            = Collections.unmodifiableSet(EnumSet.of(Collector.Characteristics.UNORDERED,
                                                     Collector.Characteristics.IDENTITY_FINISH));
    static final Set<Collector.Characteristics> CH_NOID = Collections.emptySet();

    private Collectors() { }

    /**
     * Returns a merge function, suitable for use in
     * {@link Map#merge(Object, Object, BiFunction) Map.merge()} or
     * {@link #toMap(Function, Function, BinaryOperator) toMap()}, which always
     * throws {@code IllegalStateException}.  This can be used to enforce the
     * assumption that the elements being collected are distinct.
     *
     * @param <T> the type of input arguments to the merge function
     * @return a merge function which always throw {@code IllegalStateException}
     * BinaryOperator 的 3种参数都是同一类型 这里 传入2种参数 后 抛出异常
     */
    private static <T> BinaryOperator<T> throwingMerger() {
        return (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); };
    }

    /**
     * 转换传入的参数类型
     * @param <I>
     * @param <R>
     * @return
     */
    @SuppressWarnings("unchecked")
    private static <I, R> Function<I, R> castingIdentity() {
        return i -> (R) i;
    }

    /**
     * Simple implementation class for {@code Collector}.
     *
     * @param <T> the type of elements to be collected
     * @param <R> the type of the result
     *           默认实现
     */
    static class CollectorImpl<T, A, R> implements Collector<T, A, R> {
        /**
         * 元素生成函数
         */
        private final Supplier<A> supplier;
        /**
         * 累加器  该函数是在 往容器中添加新元素时触发
         */
        private final BiConsumer<A, T> accumulator;
        /**
         * 将 2个同类型元素 结合生成 第三个元素(同类型)  应该是针对 冲突的情况 使用的函数
         */
        private final BinaryOperator<A> combiner;
        /**
         * 产生结果的函数???
         */
        private final Function<A, R> finisher;
        private final Set<Characteristics> characteristics;

        CollectorImpl(Supplier<A> supplier,
                      BiConsumer<A, T> accumulator,
                      BinaryOperator<A> combiner,
                      Function<A,R> finisher,
                      Set<Characteristics> characteristics) {
            this.supplier = supplier;
            this.accumulator = accumulator;
            this.combiner = combiner;
            this.finisher = finisher;
            this.characteristics = characteristics;
        }

        CollectorImpl(Supplier<A> supplier,
                      BiConsumer<A, T> accumulator,
                      BinaryOperator<A> combiner,
                      Set<Characteristics> characteristics) {
            // 默认使用的 finisher 就是进行强转
            this(supplier, accumulator, combiner, castingIdentity(), characteristics);
        }

        @Override
        public BiConsumer<A, T> accumulator() {
            return accumulator;
        }

        @Override
        public Supplier<A> supplier() {
            return supplier;
        }

        @Override
        public BinaryOperator<A> combiner() {
            return combiner;
        }

        @Override
        public Function<A, R> finisher() {
            return finisher;
        }

        @Override
        public Set<Characteristics> characteristics() {
            return characteristics;
        }
    }

    /**
     * Returns a {@code Collector} that accumulates the input elements into a
     * new {@code Collection}, in encounter order.  The {@code Collection} is
     * created by the provided factory.
     *
     * @param <T> the type of the input elements
     * @param <C> the type of the resulting {@code Collection}
     * @param collectionFactory a {@code Supplier} which returns a new, empty
     * {@code Collection} of the appropriate type
     * @return a {@code Collector} which collects all the input elements into a
     * {@code Collection}, in encounter order
     */
    public static <T, C extends Collection<T>>
    Collector<T, ?, C> toCollection(Supplier<C> collectionFactory) {
        // Collection::add 是默认的添加器 就是将新的元素添加到容器中
        // 使用 Collection<T>::add 这种写法 本身就是 隐藏了一个参数 (this)， 所以下面真正的样子应该是 (this, needAdd) -> {this.add(needAdd)}
        // 默认没有设置 finisher 函数
        return new CollectorImpl<>(collectionFactory, Collection<T>::add,
                                   (r1, r2) -> { r1.addAll(r2); return r1; },
                                   CH_ID);
    }

    /**
     * Returns a {@code Collector} that accumulates the input elements into a
     * new {@code List}. There are no guarantees on the type, mutability,
     * serializability, or thread-safety of the {@code List} returned; if more
     * control over the returned {@code List} is required, use {@link #toCollection(Supplier)}.
     *
     * @param <T> the type of the input elements
     * @return a {@code Collector} which collects all the input elements into a
     * {@code List}, in encounter order
     * 构建 将元素类型变成 List
     */
    public static <T>
    Collector<T, ?, List<T>> toList() {
        // 看来supplier 应该是 初始化 容器对象的  accumulate 代表当新的元素要添加到容器时    combiner 代表2个容器结合
        return new CollectorImpl<>((Supplier<List<T>>) ArrayList::new, List::add,
                                   (left, right) -> { left.addAll(right); return left; },
                                   CH_ID);
    }

    /**
     * Returns a {@code Collector} that accumulates the input elements into a
     * new {@code Set}. There are no guarantees on the type, mutability,
     * serializability, or thread-safety of the {@code Set} returned; if more
     * control over the returned {@code Set} is required, use
     * {@link #toCollection(Supplier)}.
     *
     * <p>This is an {@link Collector.Characteristics#UNORDERED unordered}
     * Collector.
     *
     * @param <T> the type of the input elements
     * @return a {@code Collector} which collects all the input elements into a
     * {@code Set}
     * 同上 只是核心函数被替换了  stream 通过传入 CollectorImpl 对象 来构建对应的对象 也就是方法的流程是在 那个对象中规定的
     */
    public static <T>
    Collector<T, ?, Set<T>> toSet() {
        return new CollectorImpl<>((Supplier<Set<T>>) HashSet::new, Set::add,
                                   (left, right) -> { left.addAll(right); return left; },
                                   CH_UNORDERED_ID);
    }

    /**
     * Returns a {@code Collector} that concatenates the input elements into a
     * {@code String}, in encounter order.
     *
     * @return a {@code Collector} that concatenates the input elements into a
     * {@code String}, in encounter order
     * 该 collector 一个 String 的容器对象
     */
    public static Collector<CharSequence, ?, String> joining() {
        return new CollectorImpl<CharSequence, StringBuilder, String>(
                StringBuilder::new, StringBuilder::append,
                (r1, r2) -> { r1.append(r2); return r1; },
                // 这里输入的 finisher  也就是将 结果输出
                StringBuilder::toString, CH_NOID);
    }

    /**
     * Returns a {@code Collector} that concatenates the input elements,
     * separated by the specified delimiter, in encounter order.
     *
     * @param delimiter the delimiter to be used between each element
     * @return A {@code Collector} which concatenates CharSequence elements,
     * separated by the specified delimiter, in encounter order
     */
    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter) {
        return joining(delimiter, "", "");
    }

    /**
     * Returns a {@code Collector} that concatenates the input elements,
     * separated by the specified delimiter, with the specified prefix and
     * suffix, in encounter order.
     *
     * @param delimiter the delimiter to be used between each element
     * @param  prefix the sequence of characters to be used at the beginning
     *                of the joined result
     * @param  suffix the sequence of characters to be used at the end
     *                of the joined result
     * @return A {@code Collector} which concatenates CharSequence elements,
     * separated by the specified delimiter, in encounter order
     * 这里使用了 StringJoiner 应该是 在指定位置进行拼接 不细看
     */
    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter,
                                                             CharSequence prefix,
                                                             CharSequence suffix) {
        return new CollectorImpl<>(
                () -> new StringJoiner(delimiter, prefix, suffix),
                StringJoiner::add, StringJoiner::merge,
                StringJoiner::toString, CH_NOID);
    }

    /**
     * {@code BinaryOperator<Map>} that merges the contents of its right
     * argument into its left argument, using the provided merge function to
     * handle duplicate keys.
     *
     * @param <K> type of the map keys
     * @param <V> type of the map values
     * @param <M> type of the map
     * @param mergeFunction A merge function suitable for
     * {@link Map#merge(Object, Object, BiFunction) Map.merge()}
     * @return a merge function for two maps、
     * 返回 一个 整合2个 容器的 函数对象
     */
    private static <K, V, M extends Map<K,V>>
    BinaryOperator<M> mapMerger(BinaryOperator<V> mergeFunction) {
        return (m1, m2) -> {
            for (Map.Entry<K,V> e : m2.entrySet())
                m1.merge(e.getKey(), e.getValue(), mergeFunction);
            return m1;
        };
    }

    /**
     * Adapts a {@code Collector} accepting elements of type {@code U} to one
     * accepting elements of type {@code T} by applying a mapping function to
     * each input element before accumulation.
     *
     * @apiNote
     * The {@code mapping()} collectors are most useful when used in a
     * multi-level reduction, such as downstream of a {@code groupingBy} or
     * {@code partitioningBy}.  For example, given a stream of
     * {@code Person}, to accumulate the set of last names in each city:
     * <pre>{@code
     *     Map<City, Set<String>> lastNamesByCity
     *         = people.stream().collect(groupingBy(Person::getCity,
     *                                              mapping(Person::getLastName, toSet())));
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @param <U> type of elements accepted by downstream collector
     * @param <A> intermediate accumulation type of the downstream collector
     * @param <R> result type of collector
     * @param mapper a function to be applied to the input elements
     * @param downstream a collector which will accept mapped values
     * @return a collector which applies the mapping function to the input
     * elements and provides the mapped results to the downstream collector
     * mapper 代表针对输入 元素的 处理函数独享 downStream 代表会接收生成元素的下游对象
     * 可以理解为 生成的 CollectorImpl 对象 本身的函数都是 下游函数 只是在元素进入时多了一步 mapper
     */
    public static <T, U, A, R>
    Collector<T, ?, R> mapping(Function<? super T, ? extends U> mapper,
                               Collector<? super U, A, R> downstream) {
        // 下游的累加器
        BiConsumer<A, ? super U> downstreamAccumulator = downstream.accumulator();
        // supplier 生成下游容器
        return new CollectorImpl<>(downstream.supplier(),
                                   // 新元素 t 传入时 先触发 mapper 之后 使用下游的 累加器去处理对象 r 应该是指代下游容器
                                   (r, t) -> downstreamAccumulator.accept(r, mapper.apply(t)),
                                   downstream.combiner(), downstream.finisher(),
                                   downstream.characteristics());
    }

    /**
     * Adapts a {@code Collector} to perform an additional finishing
     * transformation.  For example, one could adapt the {@link #toList()}
     * collector to always produce an immutable list with:
     * <pre>{@code
     *     List<String> people
     *         = people.stream().collect(collectingAndThen(toList(), Collections::unmodifiableList));
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @param <A> intermediate accumulation type of the downstream collector
     * @param <R> result type of the downstream collector
     * @param <RR> result type of the resulting collector
     * @param downstream a collector
     * @param finisher a function to be applied to the final result of the downstream collector
     * @return a collector which performs the action of the downstream collector,
     * followed by an additional finishing step
     * finisher 就是在原有 downstream上 追加一个 结束流程
     */
    public static<T,A,R,RR> Collector<T,A,RR> collectingAndThen(Collector<T,A,R> downstream,
                                                                Function<R,RR> finisher) {
        // 获取下游的 特征信息
        Set<Collector.Characteristics> characteristics = downstream.characteristics();
        // 代表没设置 终止函数吧
        if (characteristics.contains(Collector.Characteristics.IDENTITY_FINISH)) {
            if (characteristics.size() == 1)
                // 因为现在设置了 finisher 所以 特性要去掉 IDF
                characteristics = Collectors.CH_NOID;
            else {
                // 移除掉 IDF 特性
                characteristics = EnumSet.copyOf(characteristics);
                characteristics.remove(Collector.Characteristics.IDENTITY_FINISH);
                characteristics = Collections.unmodifiableSet(characteristics);
            }
        }
        return new CollectorImpl<>(downstream.supplier(),
                                   downstream.accumulator(),
                                   downstream.combiner(),
                                   downstream.finisher().andThen(finisher),
                                   characteristics);
    }

    /**
     * Returns a {@code Collector} accepting elements of type {@code T} that
     * counts the number of input elements.  If no elements are present, the
     * result is 0.
     *
     * @implSpec
     * This produces a result equivalent to:
     * <pre>{@code
     *     reducing(0L, e -> 1L, Long::sum)
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @return a {@code Collector} that counts the input elements
     * 计数函数
     */
    public static <T> Collector<T, ?, Long>
    counting() {
        // 每个进来的元素 看作是 1 然后初始值是0  累加函数是求和
        return reducing(0L, e -> 1L, Long::sum);
    }

    /**
     * Returns a {@code Collector} that produces the minimal element according
     * to a given {@code Comparator}, described as an {@code Optional<T>}.
     *
     * @implSpec
     * This produces a result equivalent to:
     * <pre>{@code
     *     reducing(BinaryOperator.minBy(comparator))
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @param comparator a {@code Comparator} for comparing elements
     * @return a {@code Collector} that produces the minimal value
     */
    public static <T> Collector<T, ?, Optional<T>>
    minBy(Comparator<? super T> comparator) {
        // BinaryOperator.minBy(comparator) 返回更小的值
        return reducing(BinaryOperator.minBy(comparator));
    }

    /**
     * Returns a {@code Collector} that produces the maximal element according
     * to a given {@code Comparator}, described as an {@code Optional<T>}.
     *
     * @implSpec
     * This produces a result equivalent to:
     * <pre>{@code
     *     reducing(BinaryOperator.maxBy(comparator))
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @param comparator a {@code Comparator} for comparing elements
     * @return a {@code Collector} that produces the maximal value
     */
    public static <T> Collector<T, ?, Optional<T>>
    maxBy(Comparator<? super T> comparator) {
        // 返回更大的值
        return reducing(BinaryOperator.maxBy(comparator));
    }

    /**
     * Returns a {@code Collector} that produces the sum of a integer-valued
     * function applied to the input elements.  If no elements are present,
     * the result is 0.
     *
     * @param <T> the type of the input elements
     * @param mapper a function extracting the property to be summed
     * @return a {@code Collector} that produces the sum of a derived property
     * ToIntFunction 代表将一个 参数转换成int 类型
     */
    public static <T> Collector<T, ?, Integer>
    summingInt(ToIntFunction<? super T> mapper) {
        return new CollectorImpl<>(
                // 生成包含一个 元素的数组
                () -> new int[1],
                // 将入参 通过 函数 转换为 int 类型并追加到 数组中
                (a, t) -> { a[0] += mapper.applyAsInt(t); },
                // 2个数组的首元素相加
                (a, b) -> { a[0] += b[0]; return a; },
                // finisher 就是返回 数组首元素
                a -> a[0], CH_NOID);
    }

    /**
     * Returns a {@code Collector} that produces the sum of a long-valued
     * function applied to the input elements.  If no elements are present,
     * the result is 0.
     *
     * @param <T> the type of the input elements
     * @param mapper a function extracting the property to be summed
     * @return a {@code Collector} that produces the sum of a derived property
     * 同上
     */
    public static <T> Collector<T, ?, Long>
    summingLong(ToLongFunction<? super T> mapper) {
        return new CollectorImpl<>(
                () -> new long[1],
                (a, t) -> { a[0] += mapper.applyAsLong(t); },
                (a, b) -> { a[0] += b[0]; return a; },
                a -> a[0], CH_NOID);
    }

    /**
     * Returns a {@code Collector} that produces the sum of a double-valued
     * function applied to the input elements.  If no elements are present,
     * the result is 0.
     *
     * <p>The sum returned can vary depending upon the order in which
     * values are recorded, due to accumulated rounding error in
     * addition of values of differing magnitudes. Values sorted by increasing
     * absolute magnitude tend to yield more accurate results.  If any recorded
     * value is a {@code NaN} or the sum is at any point a {@code NaN} then the
     * sum will be {@code NaN}.
     *
     * @param <T> the type of the input elements
     * @param mapper a function extracting the property to be summed
     * @return a {@code Collector} that produces the sum of a derived property
     * 先不看
     */
    public static <T> Collector<T, ?, Double>
    summingDouble(ToDoubleFunction<? super T> mapper) {
        /*
         * In the arrays allocated for the collect operation, index 0
         * holds the high-order bits of the running sum, index 1 holds
         * the low-order bits of the sum computed via compensated
         * summation, and index 2 holds the simple sum used to compute
         * the proper result if the stream contains infinite values of
         * the same sign.
         */
        return new CollectorImpl<>(
                () -> new double[3],
                (a, t) -> { sumWithCompensation(a, mapper.applyAsDouble(t));
                            a[2] += mapper.applyAsDouble(t);},
                (a, b) -> { sumWithCompensation(a, b[0]);
                            a[2] += b[2];
                            return sumWithCompensation(a, b[1]); },
                a -> computeFinalSum(a),
                CH_NOID);
    }

    /**
     * Incorporate a new double value using Kahan summation /
     * compensation summation.
     *
     * High-order bits of the sum are in intermediateSum[0], low-order
     * bits of the sum are in intermediateSum[1], any additional
     * elements are application-specific.
     *
     * @param intermediateSum the high-order and low-order words of the intermediate sum
     * @param value the name value to be included in the running sum
     */
    static double[] sumWithCompensation(double[] intermediateSum, double value) {
        double tmp = value - intermediateSum[1];
        double sum = intermediateSum[0];
        double velvel = sum + tmp; // Little wolf of rounding error
        intermediateSum[1] = (velvel - sum) - tmp;
        intermediateSum[0] = velvel;
        return intermediateSum;
    }

    /**
     * If the compensated sum is spuriously NaN from accumulating one
     * or more same-signed infinite values, return the
     * correctly-signed infinity stored in the simple sum.
     */
    static double computeFinalSum(double[] summands) {
        // Better error bounds to add both terms as the final sum
        double tmp = summands[0] + summands[1];
        double simpleSum = summands[summands.length - 1];
        if (Double.isNaN(tmp) && Double.isInfinite(simpleSum))
            return simpleSum;
        else
            return tmp;
    }

    /**
     * Returns a {@code Collector} that produces the arithmetic mean of an integer-valued
     * function applied to the input elements.  If no elements are present,
     * the result is 0.
     *
     * @param <T> the type of the input elements
     * @param mapper a function extracting the property to be summed
     * @return a {@code Collector} that produces the sum of a derived property
     */
    public static <T> Collector<T, ?, Double>
    averagingInt(ToIntFunction<? super T> mapper) {
        return new CollectorImpl<>(
                () -> new long[2],
                (a, t) -> { a[0] += mapper.applyAsInt(t); a[1]++; },
                (a, b) -> { a[0] += b[0]; a[1] += b[1]; return a; },
                a -> (a[1] == 0) ? 0.0d : (double) a[0] / a[1], CH_NOID);
    }

    /**
     * Returns a {@code Collector} that produces the arithmetic mean of a long-valued
     * function applied to the input elements.  If no elements are present,
     * the result is 0.
     *
     * @param <T> the type of the input elements
     * @param mapper a function extracting the property to be summed
     * @return a {@code Collector} that produces the sum of a derived property
     */
    public static <T> Collector<T, ?, Double>
    averagingLong(ToLongFunction<? super T> mapper) {
        return new CollectorImpl<>(
                () -> new long[2],
                (a, t) -> { a[0] += mapper.applyAsLong(t); a[1]++; },
                (a, b) -> { a[0] += b[0]; a[1] += b[1]; return a; },
                a -> (a[1] == 0) ? 0.0d : (double) a[0] / a[1], CH_NOID);
    }

    /**
     * Returns a {@code Collector} that produces the arithmetic mean of a double-valued
     * function applied to the input elements.  If no elements are present,
     * the result is 0.
     *
     * <p>The average returned can vary depending upon the order in which
     * values are recorded, due to accumulated rounding error in
     * addition of values of differing magnitudes. Values sorted by increasing
     * absolute magnitude tend to yield more accurate results.  If any recorded
     * value is a {@code NaN} or the sum is at any point a {@code NaN} then the
     * average will be {@code NaN}.
     *
     * @implNote The {@code double} format can represent all
     * consecutive integers in the range -2<sup>53</sup> to
     * 2<sup>53</sup>. If the pipeline has more than 2<sup>53</sup>
     * values, the divisor in the average computation will saturate at
     * 2<sup>53</sup>, leading to additional numerical errors.
     *
     * @param <T> the type of the input elements
     * @param mapper a function extracting the property to be summed
     * @return a {@code Collector} that produces the sum of a derived property
     */
    public static <T> Collector<T, ?, Double>
    averagingDouble(ToDoubleFunction<? super T> mapper) {
        /*
         * In the arrays allocated for the collect operation, index 0
         * holds the high-order bits of the running sum, index 1 holds
         * the low-order bits of the sum computed via compensated
         * summation, and index 2 holds the number of values seen.
         */
        return new CollectorImpl<>(
                () -> new double[4],
                (a, t) -> { sumWithCompensation(a, mapper.applyAsDouble(t)); a[2]++; a[3]+= mapper.applyAsDouble(t);},
                (a, b) -> { sumWithCompensation(a, b[0]); sumWithCompensation(a, b[1]); a[2] += b[2]; a[3] += b[3]; return a; },
                a -> (a[2] == 0) ? 0.0d : (computeFinalSum(a) / a[2]),
                CH_NOID);
    }

    /**
     * Returns a {@code Collector} which performs a reduction of its
     * input elements under a specified {@code BinaryOperator} using the
     * provided identity.
     *
     * @apiNote
     * The {@code reducing()} collectors are most useful when used in a
     * multi-level reduction, downstream of {@code groupingBy} or
     * {@code partitioningBy}.  To perform a simple reduction on a stream,
     * use {@link Stream#reduce(Object, BinaryOperator)}} instead.
     *
     * @param <T> element type for the input and output of the reduction
     * @param identity the identity value for the reduction (also, the value
     *                 that is returned when there are no input elements)
     * @param op a {@code BinaryOperator<T>} used to reduce the input elements
     * @return a {@code Collector} which implements the reduction operation
     *
     * @see #reducing(BinaryOperator)
     * @see #reducing(Object, Function, BinaryOperator)
     */
    public static <T> Collector<T, ?, T>
    reducing(T identity, BinaryOperator<T> op) {
        return new CollectorImpl<>(
                //将普通对象 修饰成 数组对象 使得符合 Collector 的语义
                boxSupplier(identity),
                // 当添加一个新元素时 将该元素与旧元素 一同处理后 重新设置
                (a, t) -> { a[0] = op.apply(a[0], t); },
                (a, b) -> { a[0] = op.apply(a[0], b[0]); return a; },
                a -> a[0],
                CH_NOID);
    }

    @SuppressWarnings("unchecked")
    private static <T> Supplier<T[]> boxSupplier(T identity) {
        return () -> (T[]) new Object[] { identity };
    }

    /**
     * Returns a {@code Collector} which performs a reduction of its
     * input elements under a specified {@code BinaryOperator}.  The result
     * is described as an {@code Optional<T>}.
     *
     * @apiNote
     * The {@code reducing()} collectors are most useful when used in a
     * multi-level reduction, downstream of {@code groupingBy} or
     * {@code partitioningBy}.  To perform a simple reduction on a stream,
     * use {@link Stream#reduce(BinaryOperator)} instead.
     *
     * <p>For example, given a stream of {@code Person}, to calculate tallest
     * person in each city:
     * <pre>{@code
     *     Comparator<Person> byHeight = Comparator.comparing(Person::getHeight);
     *     Map<City, Person> tallestByCity
     *         = people.stream().collect(groupingBy(Person::getCity, reducing(BinaryOperator.maxBy(byHeight))));
     * }</pre>
     *
     * @param <T> element type for the input and output of the reduction
     * @param op a {@code BinaryOperator<T>} used to reduce the input elements
     * @return a {@code Collector} which implements the reduction operation
     *
     * @see #reducing(Object, BinaryOperator)
     * @see #reducing(Object, Function, BinaryOperator)
     * 只是用一个 op 对象 进行初始化
     */
    public static <T> Collector<T, ?, Optional<T>>
    reducing(BinaryOperator<T> op) {
        class OptionalBox implements Consumer<T> {
            T value = null;
            boolean present = false;

            @Override
            public void accept(T t) {
                if (present) {
                    value = op.apply(value, t);
                }
                else {
                    value = t;
                    present = true;
                }
            }
        }

        return new CollectorImpl<T, OptionalBox, Optional<T>>(
                // 代表默认值为null  首次添加元素 会设置 value 之后就是在 value的基础上 运用某个函数 并更新结果
                OptionalBox::new, OptionalBox::accept,
                (a, b) -> { if (b.present) a.accept(b.value); return a; },
                a -> Optional.ofNullable(a.value), CH_NOID);
    }

    /**
     * Returns a {@code Collector} which performs a reduction of its
     * input elements under a specified mapping function and
     * {@code BinaryOperator}. This is a generalization of
     * {@link #reducing(Object, BinaryOperator)} which allows a transformation
     * of the elements before reduction.
     *
     * @apiNote
     * The {@code reducing()} collectors are most useful when used in a
     * multi-level reduction, downstream of {@code groupingBy} or
     * {@code partitioningBy}.  To perform a simple map-reduce on a stream,
     * use {@link Stream#map(Function)} and {@link Stream#reduce(Object, BinaryOperator)}
     * instead.
     *
     * <p>For example, given a stream of {@code Person}, to calculate the longest
     * last name of residents in each city:
     * <pre>{@code
     *     Comparator<String> byLength = Comparator.comparing(String::length);
     *     Map<City, String> longestLastNameByCity
     *         = people.stream().collect(groupingBy(Person::getCity,
     *                                              reducing(Person::getLastName, BinaryOperator.maxBy(byLength))));
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @param <U> the type of the mapped values
     * @param identity the identity value for the reduction (also, the value
     *                 that is returned when there are no input elements)
     * @param mapper a mapping function to apply to each input value
     * @param op a {@code BinaryOperator<U>} used to reduce the mapped values
     * @return a {@code Collector} implementing the map-reduce operation
     *
     * @see #reducing(Object, BinaryOperator)
     * @see #reducing(BinaryOperator)
     */
    public static <T, U>
    Collector<T, ?, U> reducing(U identity,
                                Function<? super T, ? extends U> mapper,
                                BinaryOperator<U> op) {
        return new CollectorImpl<>(
                boxSupplier(identity),
                (a, t) -> { a[0] = op.apply(a[0], mapper.apply(t)); },
                (a, b) -> { a[0] = op.apply(a[0], b[0]); return a; },
                a -> a[0], CH_NOID);
    }

    /**
     * Returns a {@code Collector} implementing a "group by" operation on
     * input elements of type {@code T}, grouping elements according to a
     * classification function, and returning the results in a {@code Map}.
     *
     * <p>The classification function maps elements to some key type {@code K}.
     * The collector produces a {@code Map<K, List<T>>} whose keys are the
     * values resulting from applying the classification function to the input
     * elements, and whose corresponding values are {@code List}s containing the
     * input elements which map to the associated key under the classification
     * function.
     *
     * <p>There are no guarantees on the type, mutability, serializability, or
     * thread-safety of the {@code Map} or {@code List} objects returned.
     * @implSpec
     * This produces a result similar to:
     * <pre>{@code
     *     groupingBy(classifier, toList());
     * }</pre>
     *
     * @implNote
     * The returned {@code Collector} is not concurrent.  For parallel stream
     * pipelines, the {@code combiner} function operates by merging the keys
     * from one map into another, which can be an expensive operation.  If
     * preservation of the order in which elements appear in the resulting {@code Map}
     * collector is not required, using {@link #groupingByConcurrent(Function)}
     * may offer better parallel performance.
     *
     * @param <T> the type of the input elements
     * @param <K> the type of the keys
     * @param classifier the classifier function mapping input elements to keys
     * @return a {@code Collector} implementing the group-by operation
     *
     * @see #groupingBy(Function, Collector)
     * @see #groupingBy(Function, Supplier, Collector)
     * @see #groupingByConcurrent(Function)
     */
    public static <T, K> Collector<T, ?, Map<K, List<T>>>
    groupingBy(Function<? super T, ? extends K> classifier) {
        // toList 返回一个 基于list 相关函数的 Collector   Function 代表将 T -> K
        return groupingBy(classifier, toList());
    }

    /**
     * Returns a {@code Collector} implementing a cascaded "group by" operation
     * on input elements of type {@code T}, grouping elements according to a
     * classification function, and then performing a reduction operation on
     * the values associated with a given key using the specified downstream
     * {@code Collector}.
     *
     * <p>The classification function maps elements to some key type {@code K}.
     * The downstream collector operates on elements of type {@code T} and
     * produces a result of type {@code D}. The resulting collector produces a
     * {@code Map<K, D>}.
     *
     * <p>There are no guarantees on the type, mutability,
     * serializability, or thread-safety of the {@code Map} returned.
     *
     * <p>For example, to compute the set of last names of people in each city:
     * <pre>{@code
     *     Map<City, Set<String>> namesByCity
     *         = people.stream().collect(groupingBy(Person::getCity,
     *                                              mapping(Person::getLastName, toSet())));
     * }</pre>
     *
     * @implNote
     * The returned {@code Collector} is not concurrent.  For parallel stream
     * pipelines, the {@code combiner} function operates by merging the keys
     * from one map into another, which can be an expensive operation.  If
     * preservation of the order in which elements are presented to the downstream
     * collector is not required, using {@link #groupingByConcurrent(Function, Collector)}
     * may offer better parallel performance.
     *
     * @param <T> the type of the input elements
     * @param <K> the type of the keys
     * @param <A> the intermediate accumulation type of the downstream collector
     * @param <D> the result type of the downstream reduction
     * @param classifier a classifier function mapping input elements to keys
     * @param downstream a {@code Collector} implementing the downstream reduction
     * @return a {@code Collector} implementing the cascaded group-by operation
     * @see #groupingBy(Function)
     *
     * @see #groupingBy(Function, Supplier, Collector)
     * @see #groupingByConcurrent(Function, Collector)
     */
    public static <T, K, A, D>
    Collector<T, ?, Map<K, D>> groupingBy(Function<? super T, ? extends K> classifier,
                                          Collector<? super T, A, D> downstream) {
        return groupingBy(classifier, HashMap::new, downstream);
    }

    /**
     * Returns a {@code Collector} implementing a cascaded "group by" operation
     * on input elements of type {@code T}, grouping elements according to a
     * classification function, and then performing a reduction operation on
     * the values associated with a given key using the specified downstream
     * {@code Collector}.  The {@code Map} produced by the Collector is created
     * with the supplied factory function.
     *
     * <p>The classification function maps elements to some key type {@code K}.
     * The downstream collector operates on elements of type {@code T} and
     * produces a result of type {@code D}. The resulting collector produces a
     * {@code Map<K, D>}.
     *
     * <p>For example, to compute the set of last names of people in each city,
     * where the city names are sorted:
     * <pre>{@code
     *     Map<City, Set<String>> namesByCity
     *         = people.stream().collect(groupingBy(Person::getCity, TreeMap::new,
     *                                              mapping(Person::getLastName, toSet())));
     * }</pre>
     *
     * @implNote
     * The returned {@code Collector} is not concurrent.  For parallel stream
     * pipelines, the {@code combiner} function operates by merging the keys
     * from one map into another, which can be an expensive operation.  If
     * preservation of the order in which elements are presented to the downstream
     * collector is not required, using {@link #groupingByConcurrent(Function, Supplier, Collector)}
     * may offer better parallel performance.
     *
     * @param <T> the type of the input elements
     * @param <K> the type of the keys
     * @param <A> the intermediate accumulation type of the downstream collector
     * @param <D> the result type of the downstream reduction
     * @param <M> the type of the resulting {@code Map}
     * @param classifier a classifier function mapping input elements to keys
     * @param downstream a {@code Collector} implementing the downstream reduction
     * @param mapFactory a function which, when called, produces a new empty
     *                   {@code Map} of the desired type
     * @return a {@code Collector} implementing the cascaded group-by operation
     *
     * @see #groupingBy(Function, Collector)
     * @see #groupingBy(Function)
     * @see #groupingByConcurrent(Function, Supplier, Collector)
     * groupingBy 好像是按照Map 进行分组???
     */
    public static <T, K, D, A, M extends Map<K, D>>
    Collector<T, ?, M> groupingBy(Function<? super T, ? extends K> classifier,
                                  Supplier<M> mapFactory,
                                  Collector<? super T, A, D> downstream) {
        // 下游容器的 提供者
        Supplier<A> downstreamSupplier = downstream.supplier();
        // 下游的累加器
        BiConsumer<A, ? super T> downstreamAccumulator = downstream.accumulator();
        // 生成新的累加器  m代表容器本身 t代表新增的元素
        BiConsumer<Map<K, A>, T> accumulator = (m, t) -> {
            // 新增的元素 要通过 Function 转换成 key 对象
            K key = Objects.requireNonNull(classifier.apply(t), "element cannot be mapped to a null key");
            // Map 的 Value 就是下游容器对象  看来是 Map<Key, Collection<?>>
            A container = m.computeIfAbsent(key, k -> downstreamSupplier.get());
            // 将 下游的 Colleciton 看作新的容器对象并加新增的元素 添加进去
            downstreamAccumulator.accept(container, t);
        };
        // combiner 起作用的时候就是 k 冲突时 value 是如何整合的  针对 使用List相关函数的 Collection Key 冲突时 就是将新的元素填充到list 中
        BinaryOperator<Map<K, A>> merger = Collectors.<K, A, Map<K, A>>mapMerger(downstream.combiner());

        // 代表上游对象的 容器生成函数
        @SuppressWarnings("unchecked")
        Supplier<Map<K, A>> mangledFactory = (Supplier<Map<K, A>>) mapFactory;

        // 如果下游包含IDF 特性 将其他特性 去掉  可能因为 存在上游 使得下游的顺序 之类的变得不确定
        if (downstream.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)) {
            return new CollectorImpl<>(mangledFactory, accumulator, merger, CH_ID);
        }
        else {
            @SuppressWarnings("unchecked")
            Function<A, A> downstreamFinisher = (Function<A, A>) downstream.finisher();
            Function<Map<K, A>, M> finisher = intermediate -> {
                // 意味着 将 v 通过 finisher 处理后重新设置到 v 中  该map的 finisher 相当于是一个桥梁 确保下游的 finisher 能起作用
                intermediate.replaceAll((k, v) -> downstreamFinisher.apply(v));
                @SuppressWarnings("unchecked")
                M castResult = (M) intermediate;
                return castResult;
            };
            // CH_NOID 代表所有特性都不能保证  为什么 CONCURRENT  和 OREDR 特性会消失 ???  可能是因为原来的List 可能是一个线程安全对象
            // 此时使用了Map 作为它的上游 那么这时任何并发操作都不能保证同步 原先List 的顺序 可能也会打乱
            return new CollectorImpl<>(mangledFactory, accumulator, merger, finisher, CH_NOID);
        }
    }

    /**
     * Returns a concurrent {@code Collector} implementing a "group by"
     * operation on input elements of type {@code T}, grouping elements
     * according to a classification function.
     *
     * <p>This is a {@link Collector.Characteristics#CONCURRENT concurrent} and
     * {@link Collector.Characteristics#UNORDERED unordered} Collector.
     *
     * <p>The classification function maps elements to some key type {@code K}.
     * The collector produces a {@code ConcurrentMap<K, List<T>>} whose keys are the
     * values resulting from applying the classification function to the input
     * elements, and whose corresponding values are {@code List}s containing the
     * input elements which map to the associated key under the classification
     * function.
     *
     * <p>There are no guarantees on the type, mutability, or serializability
     * of the {@code Map} or {@code List} objects returned, or of the
     * thread-safety of the {@code List} objects returned.
     * @implSpec
     * This produces a result similar to:
     * <pre>{@code
     *     groupingByConcurrent(classifier, toList());
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @param <K> the type of the keys
     * @param classifier a classifier function mapping input elements to keys
     * @return a concurrent, unordered {@code Collector} implementing the group-by operation
     *
     * @see #groupingBy(Function)
     * @see #groupingByConcurrent(Function, Collector)
     * @see #groupingByConcurrent(Function, Supplier, Collector)
     * 生成具备并发特性的 分组对象
     */
    public static <T, K>
    Collector<T, ?, ConcurrentMap<K, List<T>>>
    groupingByConcurrent(Function<? super T, ? extends K> classifier) {
        return groupingByConcurrent(classifier, ConcurrentHashMap::new, toList());
    }

    /**
     * Returns a concurrent {@code Collector} implementing a cascaded "group by"
     * operation on input elements of type {@code T}, grouping elements
     * according to a classification function, and then performing a reduction
     * operation on the values associated with a given key using the specified
     * downstream {@code Collector}.
     *
     * <p>This is a {@link Collector.Characteristics#CONCURRENT concurrent} and
     * {@link Collector.Characteristics#UNORDERED unordered} Collector.
     *
     * <p>The classification function maps elements to some key type {@code K}.
     * The downstream collector operates on elements of type {@code T} and
     * produces a result of type {@code D}. The resulting collector produces a
     * {@code Map<K, D>}.
     *
     * <p>For example, to compute the set of last names of people in each city,
     * where the city names are sorted:
     * <pre>{@code
     *     ConcurrentMap<City, Set<String>> namesByCity
     *         = people.stream().collect(groupingByConcurrent(Person::getCity,
     *                                                        mapping(Person::getLastName, toSet())));
     * }</pre>
     *
     * @param <T> the type of the input elements
     * @param <K> the type of the keys
     * @param <A> the intermediate accumulation type of the downstream collector
     * @param <D> the result type of the downstream reduction
     * @param classifier a classifier function mapping input elements to keys
     * @param downstream a {@code Collector} implementing the downstream reduction
     * @return a concurrent, unordered {@code Collector} implementing the cascaded group-by operation
     *
     * @see #groupingBy(Function, Collector)
     * @see #groupingByConcurrent(Function)
     * @see #groupingByConcurrent(Function, Supplier, Collector)
     */
    public static <T, K, A, D>
    Collector<T, ?, ConcurrentMap<K, D>> groupingByConcurrent(Function<? super T, ? extends K> classifier,
                                                              Collector<? super T, A, D> downstream) {
        return groupingByConcurrent(classifier, ConcurrentHashMap::new, downstream);
    }

    /**
     * Returns a concurrent {@code Collector} implementing a cascaded "group by"
     * operation on input elements of type {@code T}, grouping elements
     * according to a classification function, and then performing a reduction
     * operation on the values associated with a given key using the specified
     * downstream {@code Collector}.  The {@code ConcurrentMap} produced by the
     * Collector is created with the supplied factory function.
     *
     * <p>This is a {@link Collector.Characteristics#CONCURRENT concurrent} and
     * {@link Collector.Characteristics#UNORDERED unordered} Collector.
     *
     * <p>The classification function maps elements to some key type {@code K}.
     * The downstream collector operates on elements of type {@code T} and
     * produces a result of type {@code D}. The resulting collector produces a
     * {@code Map<K, D>}.
     *
     * <p>For example, to compute the set of last names of people in each city,
     * where the city names are sorted:
     * <pre>{@code
     *     ConcurrentMap<City, Set<String>> namesByCity
     *         = people.stream().collect(groupingBy(Person::getCity, ConcurrentSkipListMap::new,
     *                                              mapping(Person::getLastName, toSet())));
     * }</pre>
     *
     *
     * @param <T> the type of the input elements
     * @param <K> the type of the keys
     * @param <A> the intermediate accumulation type of the downstream collector
     * @param <D> the result type of the downstream reduction
     * @param <M> the type of the resulting {@code ConcurrentMap}
     * @param classifier a classifier function mapping input elements to keys
     * @param downstream a {@code Collector} implementing the downstream reduction
     * @param mapFactory a function which, when called, produces a new empty
     *                   {@code ConcurrentMap} of the desired type
     * @return a concurrent, unordered {@code Collector} implementing the cascaded group-by operation
     *
     * @see #groupingByConcurrent(Function)
     * @see #groupingByConcurrent(Function, Collector)
     * @see #groupingBy(Function, Supplier, Collector)
     * 生成并发容器
     */
    public static <T, K, A, D, M extends ConcurrentMap<K, D>>
    Collector<T, ?, M> groupingByConcurrent(Function<? super T, ? extends K> classifier,
                                            Supplier<M> mapFactory,
                                            Collector<? super T, A, D> downstream) {
        Supplier<A> downstreamSupplier = downstream.supplier();
        BiConsumer<A, ? super T> downstreamAccumulator = downstream.accumulator();
        BinaryOperator<ConcurrentMap<K, A>> merger = Collectors.<K, A, ConcurrentMap<K, A>>mapMerger(downstream.combiner());
        @SuppressWarnings("unchecked")
        Supplier<ConcurrentMap<K, A>> mangledFactory = (Supplier<ConcurrentMap<K, A>>) mapFactory;
        BiConsumer<ConcurrentMap<K, A>, T> accumulator;
        // 如果下游 包含并发特性  groupingBy 是不要求并发特性的
        if (downstream.characteristics().contains(Collector.Characteristics.CONCURRENT)) {
            accumulator = (m, t) -> {
                K key = Objects.requireNonNull(classifier.apply(t), "element cannot be mapped to a null key");
                A resultContainer = m.computeIfAbsent(key, k -> downstreamSupplier.get());
                downstreamAccumulator.accept(resultContainer, t);
            };
        }
        else {
            // 为下游 对象 添加内置锁 保证线程安全
            accumulator = (m, t) -> {
                K key = Objects.requireNonNull(classifier.apply(t), "element cannot be mapped to a null key");
                A resultContainer = m.computeIfAbsent(key, k -> downstreamSupplier.get());
                synchronized (resultContainer) {
                    downstreamAccumulator.accept(resultContainer, t);
                }
            };
        }

        // 生成 CONCURRENT,NO_ORDER 特性
        if (downstream.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)) {
            return new CollectorImpl<>(mangledFactory, accumulator, merger, CH_CONCURRENT_ID);
        }
        else {
            @SuppressWarnings("unchecked")
            Function<A, A> downstreamFinisher = (Function<A, A>) downstream.finisher();
            Function<ConcurrentMap<K, A>, M> finisher = intermediate -> {
                intermediate.replaceAll((k, v) -> downstreamFinisher.apply(v));
                @SuppressWarnings("unchecked")
                M castResult = (M) intermediate;
                return castResult;
            };
            return new CollectorImpl<>(mangledFactory, accumulator, merger, finisher, CH_CONCURRENT_NOID);
        }
    }

    /**
     * Returns a {@code Collector} which partitions the input elements according
     * to a {@code Predicate}, and organizes them into a
     * {@code Map<Boolean, List<T>>}.
     *
     * There are no guarantees on the type, mutability,
     * serializability, or thread-safety of the {@code Map} returned.
     *
     * @param <T> the type of the input elements
     * @param predicate a predicate used for classifying input elements
     * @return a {@code Collector} implementing the partitioning operation
     *
     * @see #partitioningBy(Predicate, Collector)
     * 分裂函数  默认使用基于 List函数的 Collector
     */
    public static <T>
    Collector<T, ?, Map<Boolean, List<T>>> partitioningBy(Predicate<? super T> predicate) {
        return partitioningBy(predicate, toList());
    }

    /**
     * Returns a {@code Collector} which partitions the input elements according
     * to a {@code Predicate}, reduces the values in each partition according to
     * another {@code Collector}, and organizes them into a
     * {@code Map<Boolean, D>} whose values are the result of the downstream
     * reduction.
     *
     * <p>There are no guarantees on the type, mutability,
     * serializability, or thread-safety of the {@code Map} returned.
     *
     * @param <T> the type of the input elements
     * @param <A> the intermediate accumulation type of the downstream collector
     * @param <D> the result type of the downstream reduction
     * @param predicate a predicate used for classifying input elements
     * @param downstream a {@code Collector} implementing the downstream
     *                   reduction
     * @return a {@code Collector} implementing the cascaded partitioning
     *         operation
     *
     * @see #partitioningBy(Predicate)
     */
    public static <T, D, A>
    Collector<T, ?, Map<Boolean, D>> partitioningBy(Predicate<? super T> predicate,
                                                    Collector<? super T, A, D> downstream) {
        // 获取下游的 累加器
        BiConsumer<A, ? super T> downstreamAccumulator = downstream.accumulator();
        // 指定了 result 为 Partition 对象
        BiConsumer<Partition<A>, T> accumulator = (result, t) ->
                // 通过谓语条件判断 新元素 属于哪个分组 如果是true 将 true下 对应的数据 与 本次新数据进行累加  可能result是 List类型 就会调用 list.add 将元素添加到列表中...
                downstreamAccumulator.accept(predicate.test(t) ? result.forTrue : result.forFalse, t);
        // 获取下游的结合器
        BinaryOperator<A> op = downstream.combiner();
        BinaryOperator<Partition<A>> merger = (left, right) ->
                new Partition<>(op.apply(left.forTrue, right.forTrue),
                                op.apply(left.forFalse, right.forFalse));
        // 下游 提供者
        Supplier<Partition<A>> supplier = () ->
                new Partition<>(downstream.supplier().get(),
                                downstream.supplier().get());
        if (downstream.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)) {
            return new CollectorImpl<>(supplier, accumulator, merger, CH_ID);
        }
        else {
            Function<Partition<A>, Map<Boolean, D>> finisher = par ->
                    new Partition<>(downstream.finisher().apply(par.forTrue),
                                    downstream.finisher().apply(par.forFalse));
            return new CollectorImpl<>(supplier, accumulator, merger, finisher, CH_NOID);
        }
    }

    /**
     * Returns a {@code Collector} that accumulates elements into a
     * {@code Map} whose keys and values are the result of applying the provided
     * mapping functions to the input elements.
     *
     * <p>If the mapped keys contains duplicates (according to
     * {@link Object#equals(Object)}), an {@code IllegalStateException} is
     * thrown when the collection operation is performed.  If the mapped keys
     * may have duplicates, use {@link #toMap(Function, Function, BinaryOperator)}
     * instead.
     *
     * @apiNote
     * It is common for either the key or the value to be the input elements.
     * In this case, the utility method
     * {@link java.util.function.Function#identity()} may be helpful.
     * For example, the following produces a {@code Map} mapping
     * students to their grade point average:
     * <pre>{@code
     *     Map<Student, Double> studentToGPA
     *         students.stream().collect(toMap(Functions.identity(),
     *                                         student -> computeGPA(student)));
     * }</pre>
     * And the following produces a {@code Map} mapping a unique identifier to
     * students:
     * <pre>{@code
     *     Map<String, Student> studentIdToStudent
     *         students.stream().collect(toMap(Student::getId,
     *                                         Functions.identity());
     * }</pre>
     *
     * @implNote
     * The returned {@code Collector} is not concurrent.  For parallel stream
     * pipelines, the {@code combiner} function operates by merging the keys
     * from one map into another, which can be an expensive operation.  If it is
     * not required that results are inserted into the {@code Map} in encounter
     * order, using {@link #toConcurrentMap(Function, Function)}
     * may offer better parallel performance.
     *
     * @param <T> the type of the input elements
     * @param <K> the output type of the key mapping function
     * @param <U> the output type of the value mapping function
     * @param keyMapper a mapping function to produce keys
     * @param valueMapper a mapping function to produce values
     * @return a {@code Collector} which collects elements into a {@code Map}
     * whose keys and values are the result of applying mapping functions to
     * the input elements
     *
     * @see #toMap(Function, Function, BinaryOperator)
     * @see #toMap(Function, Function, BinaryOperator, Supplier)
     * @see #toConcurrentMap(Function, Function)
     * 传入一个生成 key 一个生成value 的函数对象 初始化
     */
    public static <T, K, U>
    Collector<T, ?, Map<K,U>> toMap(Function<? super T, ? extends K> keyMapper,
                                    Function<? super T, ? extends U> valueMapper) {
        return toMap(keyMapper, valueMapper, throwingMerger(), HashMap::new);
    }

    /**
     * Returns a {@code Collector} that accumulates elements into a
     * {@code Map} whose keys and values are the result of applying the provided
     * mapping functions to the input elements.
     *
     * <p>If the mapped
     * keys contains duplicates (according to {@link Object#equals(Object)}),
     * the value mapping function is applied to each equal element, and the
     * results are merged using the provided merging function.
     *
     * @apiNote
     * There are multiple ways to deal with collisions between multiple elements
     * mapping to the same key.  The other forms of {@code toMap} simply use
     * a merge function that throws unconditionally, but you can easily write
     * more flexible merge policies.  For example, if you have a stream
     * of {@code Person}, and you want to produce a "phone book" mapping name to
     * address, but it is possible that two persons have the same name, you can
     * do as follows to gracefully deals with these collisions, and produce a
     * {@code Map} mapping names to a concatenated list of addresses:
     * <pre>{@code
     *     Map<String, String> phoneBook
     *         people.stream().collect(toMap(Person::getName,
     *                                       Person::getAddress,
     *                                       (s, a) -> s + ", " + a));
     * }</pre>
     *
     * @implNote
     * The returned {@code Collector} is not concurrent.  For parallel stream
     * pipelines, the {@code combiner} function operates by merging the keys
     * from one map into another, which can be an expensive operation.  If it is
     * not required that results are merged into the {@code Map} in encounter
     * order, using {@link #toConcurrentMap(Function, Function, BinaryOperator)}
     * may offer better parallel performance.
     *
     * @param <T> the type of the input elements
     * @param <K> the output type of the key mapping function
     * @param <U> the output type of the value mapping function
     * @param keyMapper a mapping function to produce keys
     * @param valueMapper a mapping function to produce values
     * @param mergeFunction a merge function, used to resolve collisions between
     *                      values associated with the same key, as supplied
     *                      to {@link Map#merge(Object, Object, BiFunction)}
     * @return a {@code Collector} which collects elements into a {@code Map}
     * whose keys are the result of applying a key mapping function to the input
     * elements, and whose values are the result of applying a value mapping
     * function to all input elements equal to the key and combining them
     * using the merge function
     *
     * @see #toMap(Function, Function)
     * @see #toMap(Function, Function, BinaryOperator, Supplier)
     * @see #toConcurrentMap(Function, Function, BinaryOperator)
     */
    public static <T, K, U>
    Collector<T, ?, Map<K,U>> toMap(Function<? super T, ? extends K> keyMapper,
                                    Function<? super T, ? extends U> valueMapper,
                                    BinaryOperator<U> mergeFunction) {
        return toMap(keyMapper, valueMapper, mergeFunction, HashMap::new);
    }

    /**
     * Returns a {@code Collector} that accumulates elements into a
     * {@code Map} whose keys and values are the result of applying the provided
     * mapping functions to the input elements.
     *
     * <p>If the mapped
     * keys contains duplicates (according to {@link Object#equals(Object)}),
     * the value mapping function is applied to each equal element, and the
     * results are merged using the provided merging function.  The {@code Map}
     * is created by a provided supplier function.
     *
     * @implNote
     * The returned {@code Collector} is not concurrent.  For parallel stream
     * pipelines, the {@code combiner} function operates by merging the keys
     * from one map into another, which can be an expensive operation.  If it is
     * not required that results are merged into the {@code Map} in encounter
     * order, using {@link #toConcurrentMap(Function, Function, BinaryOperator, Supplier)}
     * may offer better parallel performance.
     *
     * @param <T> the type of the input elements
     * @param <K> the output type of the key mapping function
     * @param <U> the output type of the value mapping function
     * @param <M> the type of the resulting {@code Map}
     * @param keyMapper a mapping function to produce keys
     * @param valueMapper a mapping function to produce values
     * @param mergeFunction a merge function, used to resolve collisions between
     *                      values associated with the same key, as supplied
     *                      to {@link Map#merge(Object, Object, BiFunction)}
     * @param mapSupplier a function which returns a new, empty {@code Map} into
     *                    which the results will be inserted
     * @return a {@code Collector} which collects elements into a {@code Map}
     * whose keys are the result of applying a key mapping function to the input
     * elements, and whose values are the result of applying a value mapping
     * function to all input elements equal to the key and combining them
     * using the merge function
     *
     * @see #toMap(Function, Function)
     * @see #toMap(Function, Function, BinaryOperator)
     * @see #toConcurrentMap(Function, Function, BinaryOperator, Supplier)
     */
    public static <T, K, U, M extends Map<K, U>>
    Collector<T, ?, M> toMap(Function<? super T, ? extends K> keyMapper,
                                Function<? super T, ? extends U> valueMapper,
                                BinaryOperator<U> mergeFunction,
                                Supplier<M> mapSupplier) {
        BiConsumer<M, T> accumulator
                = (map, element) -> map.merge(keyMapper.apply(element),
                                              valueMapper.apply(element), mergeFunction);
        return new CollectorImpl<>(mapSupplier, accumulator, mapMerger(mergeFunction), CH_ID);
    }

    /**
     * Returns a concurrent {@code Collector} that accumulates elements into a
     * {@code ConcurrentMap} whose keys and values are the result of applying
     * the provided mapping functions to the input elements.
     *
     * <p>If the mapped keys contains duplicates (according to
     * {@link Object#equals(Object)}), an {@code IllegalStateException} is
     * thrown when the collection operation is performed.  If the mapped keys
     * may have duplicates, use
     * {@link #toConcurrentMap(Function, Function, BinaryOperator)} instead.
     *
     * @apiNote
     * It is common for either the key or the value to be the input elements.
     * In this case, the utility method
     * {@link java.util.function.Function#identity()} may be helpful.
     * For example, the following produces a {@code Map} mapping
     * students to their grade point average:
     * <pre>{@code
     *     Map<Student, Double> studentToGPA
     *         students.stream().collect(toMap(Functions.identity(),
     *                                         student -> computeGPA(student)));
     * }</pre>
     * And the following produces a {@code Map} mapping a unique identifier to
     * students:
     * <pre>{@code
     *     Map<String, Student> studentIdToStudent
     *         students.stream().collect(toConcurrentMap(Student::getId,
     *                                                   Functions.identity());
     * }</pre>
     *
     * <p>This is a {@link Collector.Characteristics#CONCURRENT concurrent} and
     * {@link Collector.Characteristics#UNORDERED unordered} Collector.
     *
     * @param <T> the type of the input elements
     * @param <K> the output type of the key mapping function
     * @param <U> the output type of the value mapping function
     * @param keyMapper the mapping function to produce keys
     * @param valueMapper the mapping function to produce values
     * @return a concurrent, unordered {@code Collector} which collects elements into a
     * {@code ConcurrentMap} whose keys are the result of applying a key mapping
     * function to the input elements, and whose values are the result of
     * applying a value mapping function to the input elements
     *
     * @see #toMap(Function, Function)
     * @see #toConcurrentMap(Function, Function, BinaryOperator)
     * @see #toConcurrentMap(Function, Function, BinaryOperator, Supplier)
     */
    public static <T, K, U>
    Collector<T, ?, ConcurrentMap<K,U>> toConcurrentMap(Function<? super T, ? extends K> keyMapper,
                                                        Function<? super T, ? extends U> valueMapper) {
        return toConcurrentMap(keyMapper, valueMapper, throwingMerger(), ConcurrentHashMap::new);
    }

    /**
     * Returns a concurrent {@code Collector} that accumulates elements into a
     * {@code ConcurrentMap} whose keys and values are the result of applying
     * the provided mapping functions to the input elements.
     *
     * <p>If the mapped keys contains duplicates (according to {@link Object#equals(Object)}),
     * the value mapping function is applied to each equal element, and the
     * results are merged using the provided merging function.
     *
     * @apiNote
     * There are multiple ways to deal with collisions between multiple elements
     * mapping to the same key.  The other forms of {@code toConcurrentMap} simply use
     * a merge function that throws unconditionally, but you can easily write
     * more flexible merge policies.  For example, if you have a stream
     * of {@code Person}, and you want to produce a "phone book" mapping name to
     * address, but it is possible that two persons have the same name, you can
     * do as follows to gracefully deals with these collisions, and produce a
     * {@code Map} mapping names to a concatenated list of addresses:
     * <pre>{@code
     *     Map<String, String> phoneBook
     *         people.stream().collect(toConcurrentMap(Person::getName,
     *                                                 Person::getAddress,
     *                                                 (s, a) -> s + ", " + a));
     * }</pre>
     *
     * <p>This is a {@link Collector.Characteristics#CONCURRENT concurrent} and
     * {@link Collector.Characteristics#UNORDERED unordered} Collector.
     *
     * @param <T> the type of the input elements
     * @param <K> the output type of the key mapping function
     * @param <U> the output type of the value mapping function
     * @param keyMapper a mapping function to produce keys
     * @param valueMapper a mapping function to produce values
     * @param mergeFunction a merge function, used to resolve collisions between
     *                      values associated with the same key, as supplied
     *                      to {@link Map#merge(Object, Object, BiFunction)}
     * @return a concurrent, unordered {@code Collector} which collects elements into a
     * {@code ConcurrentMap} whose keys are the result of applying a key mapping
     * function to the input elements, and whose values are the result of
     * applying a value mapping function to all input elements equal to the key
     * and combining them using the merge function
     *
     * @see #toConcurrentMap(Function, Function)
     * @see #toConcurrentMap(Function, Function, BinaryOperator, Supplier)
     * @see #toMap(Function, Function, BinaryOperator)
     */
    public static <T, K, U>
    Collector<T, ?, ConcurrentMap<K,U>>
    toConcurrentMap(Function<? super T, ? extends K> keyMapper,
                    Function<? super T, ? extends U> valueMapper,
                    BinaryOperator<U> mergeFunction) {
        return toConcurrentMap(keyMapper, valueMapper, mergeFunction, ConcurrentHashMap::new);
    }

    /**
     * Returns a concurrent {@code Collector} that accumulates elements into a
     * {@code ConcurrentMap} whose keys and values are the result of applying
     * the provided mapping functions to the input elements.
     *
     * <p>If the mapped keys contains duplicates (according to {@link Object#equals(Object)}),
     * the value mapping function is applied to each equal element, and the
     * results are merged using the provided merging function.  The
     * {@code ConcurrentMap} is created by a provided supplier function.
     *
     * <p>This is a {@link Collector.Characteristics#CONCURRENT concurrent} and
     * {@link Collector.Characteristics#UNORDERED unordered} Collector.
     *
     * @param <T> the type of the input elements
     * @param <K> the output type of the key mapping function
     * @param <U> the output type of the value mapping function
     * @param <M> the type of the resulting {@code ConcurrentMap}
     * @param keyMapper a mapping function to produce keys
     * @param valueMapper a mapping function to produce values
     * @param mergeFunction a merge function, used to resolve collisions between
     *                      values associated with the same key, as supplied
     *                      to {@link Map#merge(Object, Object, BiFunction)}
     * @param mapSupplier a function which returns a new, empty {@code Map} into
     *                    which the results will be inserted
     * @return a concurrent, unordered {@code Collector} which collects elements into a
     * {@code ConcurrentMap} whose keys are the result of applying a key mapping
     * function to the input elements, and whose values are the result of
     * applying a value mapping function to all input elements equal to the key
     * and combining them using the merge function
     *
     * @see #toConcurrentMap(Function, Function)
     * @see #toConcurrentMap(Function, Function, BinaryOperator)
     * @see #toMap(Function, Function, BinaryOperator, Supplier)
     */
    public static <T, K, U, M extends ConcurrentMap<K, U>>
    Collector<T, ?, M> toConcurrentMap(Function<? super T, ? extends K> keyMapper,
                                       Function<? super T, ? extends U> valueMapper,
                                       BinaryOperator<U> mergeFunction,
                                       Supplier<M> mapSupplier) {
        BiConsumer<M, T> accumulator
                = (map, element) -> map.merge(keyMapper.apply(element),
                                              valueMapper.apply(element), mergeFunction);
        return new CollectorImpl<>(mapSupplier, accumulator, mapMerger(mergeFunction), CH_CONCURRENT_ID);
    }

    /**
     * Returns a {@code Collector} which applies an {@code int}-producing
     * mapping function to each input element, and returns summary statistics
     * for the resulting values.
     *
     * @param <T> the type of the input elements
     * @param mapper a mapping function to apply to each element
     * @return a {@code Collector} implementing the summary-statistics reduction
     *
     * @see #summarizingDouble(ToDoubleFunction)
     * @see #summarizingLong(ToLongFunction)
     */
    public static <T>
    Collector<T, ?, IntSummaryStatistics> summarizingInt(ToIntFunction<? super T> mapper) {
        return new CollectorImpl<T, IntSummaryStatistics, IntSummaryStatistics>(
                IntSummaryStatistics::new,
                (r, t) -> r.accept(mapper.applyAsInt(t)),
                (l, r) -> { l.combine(r); return l; }, CH_ID);
    }

    /**
     * Returns a {@code Collector} which applies an {@code long}-producing
     * mapping function to each input element, and returns summary statistics
     * for the resulting values.
     *
     * @param <T> the type of the input elements
     * @param mapper the mapping function to apply to each element
     * @return a {@code Collector} implementing the summary-statistics reduction
     *
     * @see #summarizingDouble(ToDoubleFunction)
     * @see #summarizingInt(ToIntFunction)
     */
    public static <T>
    Collector<T, ?, LongSummaryStatistics> summarizingLong(ToLongFunction<? super T> mapper) {
        return new CollectorImpl<T, LongSummaryStatistics, LongSummaryStatistics>(
                LongSummaryStatistics::new,
                (r, t) -> r.accept(mapper.applyAsLong(t)),
                (l, r) -> { l.combine(r); return l; }, CH_ID);
    }

    /**
     * Returns a {@code Collector} which applies an {@code double}-producing
     * mapping function to each input element, and returns summary statistics
     * for the resulting values.
     *
     * @param <T> the type of the input elements
     * @param mapper a mapping function to apply to each element
     * @return a {@code Collector} implementing the summary-statistics reduction
     *
     * @see #summarizingLong(ToLongFunction)
     * @see #summarizingInt(ToIntFunction)
     */
    public static <T>
    Collector<T, ?, DoubleSummaryStatistics> summarizingDouble(ToDoubleFunction<? super T> mapper) {
        return new CollectorImpl<T, DoubleSummaryStatistics, DoubleSummaryStatistics>(
                DoubleSummaryStatistics::new,
                (r, t) -> r.accept(mapper.applyAsDouble(t)),
                (l, r) -> { l.combine(r); return l; }, CH_ID);
    }

    /**
     * Implementation class used by partitioningBy.
     * 分区对象 推测 只存在2个Key 一个 true 一个 false  然后每个 T 可能是一个容器类 通过谓语判断 成功的对象 添加到 对应的容器中
     */
    private static final class Partition<T>
            extends AbstractMap<Boolean, T>
            implements Map<Boolean, T> {
        final T forTrue;
        final T forFalse;

        Partition(T forTrue, T forFalse) {
            this.forTrue = forTrue;
            this.forFalse = forFalse;
        }

        @Override
        public Set<Map.Entry<Boolean, T>> entrySet() {
            return new AbstractSet<Map.Entry<Boolean, T>>() {
                @Override
                public Iterator<Map.Entry<Boolean, T>> iterator() {
                    Map.Entry<Boolean, T> falseEntry = new SimpleImmutableEntry<>(false, forFalse);
                    Map.Entry<Boolean, T> trueEntry = new SimpleImmutableEntry<>(true, forTrue);
                    return Arrays.asList(falseEntry, trueEntry).iterator();
                }

                @Override
                public int size() {
                    return 2;
                }
            };
        }
    }
}
