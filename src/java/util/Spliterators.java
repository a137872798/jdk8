/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * Static classes and methods for operating on or creating instances of
 * {@link Spliterator} and its primitive specializations
 * {@link Spliterator.OfInt}, {@link Spliterator.OfLong}, and
 * {@link Spliterator.OfDouble}.
 *
 * @see Spliterator
 * @since 1.8
 * 并行迭代器 工具类
 */
public final class Spliterators {

    // Suppresses default constructor, ensuring non-instantiability.
    private Spliterators() {}

    // Empty spliterators

    /**
     * Creates an empty {@code Spliterator}
     *
     * <p>The empty spliterator reports {@link Spliterator#SIZED} and
     * {@link Spliterator#SUBSIZED}.  Calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @param <T> Type of elements
     * @return An empty spliterator
     * 返回一个空的 并行迭代器
     */
    @SuppressWarnings("unchecked")
    public static <T> Spliterator<T> emptySpliterator() {
        return (Spliterator<T>) EMPTY_SPLITERATOR;
    }

    private static final Spliterator<Object> EMPTY_SPLITERATOR =
            new EmptySpliterator.OfRef<>();

    /**
     * Creates an empty {@code Spliterator.OfInt}
     *
     * <p>The empty spliterator reports {@link Spliterator#SIZED} and
     * {@link Spliterator#SUBSIZED}.  Calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return An empty spliterator
     * 返回 泛型是 Int类型的 空并行迭代器
     */
    public static Spliterator.OfInt emptyIntSpliterator() {
        return EMPTY_INT_SPLITERATOR;
    }

    private static final Spliterator.OfInt EMPTY_INT_SPLITERATOR =
            new EmptySpliterator.OfInt();

    /**
     * Creates an empty {@code Spliterator.OfLong}
     *
     * <p>The empty spliterator reports {@link Spliterator#SIZED} and
     * {@link Spliterator#SUBSIZED}.  Calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return An empty spliterator
     * 返回 泛型是Long的 并行迭代器
     */
    public static Spliterator.OfLong emptyLongSpliterator() {
        return EMPTY_LONG_SPLITERATOR;
    }

    private static final Spliterator.OfLong EMPTY_LONG_SPLITERATOR =
            new EmptySpliterator.OfLong();

    /**
     * Creates an empty {@code Spliterator.OfDouble}
     *
     * <p>The empty spliterator reports {@link Spliterator#SIZED} and
     * {@link Spliterator#SUBSIZED}.  Calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return An empty spliterator
     */
    public static Spliterator.OfDouble emptyDoubleSpliterator() {
        return EMPTY_DOUBLE_SPLITERATOR;
    }

    private static final Spliterator.OfDouble EMPTY_DOUBLE_SPLITERATOR =
            new EmptySpliterator.OfDouble();

    // Array-based spliterators

    /**
     * Creates a {@code Spliterator} covering the elements of a given array,
     * using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(Object[])}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report; it is common to
     * additionally specify {@code IMMUTABLE} and {@code ORDERED}.
     *
     * @param <T> Type of elements
     * @param array The array, assumed to be unmodified during use
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @see Arrays#spliterator(Object[])
     * 使用传入的元素生成一个 并行迭代器  additionalCharacteristics 代表追加的特性
     */
    public static <T> Spliterator<T> spliterator(Object[] array,
                                                 int additionalCharacteristics) {
        return new ArraySpliterator<>(Objects.requireNonNull(array),
                                      additionalCharacteristics);
    }

    /**
     * Creates a {@code Spliterator} covering a range of elements of a given
     * array, using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(Object[])}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report; it is common to
     * additionally specify {@code IMMUTABLE} and {@code ORDERED}.
     *
     * @param <T> Type of elements
     * @param array The array, assumed to be unmodified during use
     * @param fromIndex The least index (inclusive) to cover
     * @param toIndex One past the greatest index to cover
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @throws ArrayIndexOutOfBoundsException if {@code fromIndex} is negative,
     *         {@code toIndex} is less than {@code fromIndex}, or
     *         {@code toIndex} is greater than the array size
     * @see Arrays#spliterator(Object[], int, int)
     */
    public static <T> Spliterator<T> spliterator(Object[] array, int fromIndex, int toIndex,
                                                 int additionalCharacteristics) {
        // 判断偏移量是否合法
        checkFromToBounds(Objects.requireNonNull(array).length, fromIndex, toIndex);
        return new ArraySpliterator<>(array, fromIndex, toIndex, additionalCharacteristics);
    }

    /**
     * Creates a {@code Spliterator.OfInt} covering the elements of a given array,
     * using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(int[])}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report; it is common to
     * additionally specify {@code IMMUTABLE} and {@code ORDERED}.
     *
     * @param array The array, assumed to be unmodified during use
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @see Arrays#spliterator(int[])
     * 生成 基于 int[] 的分裂迭代器
     */
    public static Spliterator.OfInt spliterator(int[] array,
                                                int additionalCharacteristics) {
        return new IntArraySpliterator(Objects.requireNonNull(array), additionalCharacteristics);
    }

    /**
     * Creates a {@code Spliterator.OfInt} covering a range of elements of a
     * given array, using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(int[], int, int)}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report; it is common to
     * additionally specify {@code IMMUTABLE} and {@code ORDERED}.
     *
     * @param array The array, assumed to be unmodified during use
     * @param fromIndex The least index (inclusive) to cover
     * @param toIndex One past the greatest index to cover
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @throws ArrayIndexOutOfBoundsException if {@code fromIndex} is negative,
     *         {@code toIndex} is less than {@code fromIndex}, or
     *         {@code toIndex} is greater than the array size
     * @see Arrays#spliterator(int[], int, int)
     */
    public static Spliterator.OfInt spliterator(int[] array, int fromIndex, int toIndex,
                                                int additionalCharacteristics) {
        checkFromToBounds(Objects.requireNonNull(array).length, fromIndex, toIndex);
        return new IntArraySpliterator(array, fromIndex, toIndex, additionalCharacteristics);
    }

    /**
     * Creates a {@code Spliterator.OfLong} covering the elements of a given array,
     * using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(long[])}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report; it is common to
     * additionally specify {@code IMMUTABLE} and {@code ORDERED}.
     *
     * @param array The array, assumed to be unmodified during use
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @see Arrays#spliterator(long[])
     */
    public static Spliterator.OfLong spliterator(long[] array,
                                                 int additionalCharacteristics) {
        return new LongArraySpliterator(Objects.requireNonNull(array), additionalCharacteristics);
    }

    /**
     * Creates a {@code Spliterator.OfLong} covering a range of elements of a
     * given array, using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(long[], int, int)}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report.  (For example, if it is
     * known the array will not be further modified, specify {@code IMMUTABLE};
     * if the array data is considered to have an an encounter order, specify
     * {@code ORDERED}).  The method {@link Arrays#spliterator(long[], int, int)} can
     * often be used instead, which returns a spliterator that reports
     * {@code SIZED}, {@code SUBSIZED}, {@code IMMUTABLE}, and {@code ORDERED}.
     *
     * @param array The array, assumed to be unmodified during use
     * @param fromIndex The least index (inclusive) to cover
     * @param toIndex One past the greatest index to cover
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @throws ArrayIndexOutOfBoundsException if {@code fromIndex} is negative,
     *         {@code toIndex} is less than {@code fromIndex}, or
     *         {@code toIndex} is greater than the array size
     * @see Arrays#spliterator(long[], int, int)
     */
    public static Spliterator.OfLong spliterator(long[] array, int fromIndex, int toIndex,
                                                 int additionalCharacteristics) {
        checkFromToBounds(Objects.requireNonNull(array).length, fromIndex, toIndex);
        return new LongArraySpliterator(array, fromIndex, toIndex, additionalCharacteristics);
    }

    /**
     * Creates a {@code Spliterator.OfDouble} covering the elements of a given array,
     * using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(double[])}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report; it is common to
     * additionally specify {@code IMMUTABLE} and {@code ORDERED}.
     *
     * @param array The array, assumed to be unmodified during use
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @see Arrays#spliterator(double[])
     */
    public static Spliterator.OfDouble spliterator(double[] array,
                                                   int additionalCharacteristics) {
        return new DoubleArraySpliterator(Objects.requireNonNull(array), additionalCharacteristics);
    }

    /**
     * Creates a {@code Spliterator.OfDouble} covering a range of elements of a
     * given array, using a customized set of spliterator characteristics.
     *
     * <p>This method is provided as an implementation convenience for
     * Spliterators which store portions of their elements in arrays, and need
     * fine control over Spliterator characteristics.  Most other situations in
     * which a Spliterator for an array is needed should use
     * {@link Arrays#spliterator(double[], int, int)}.
     *
     * <p>The returned spliterator always reports the characteristics
     * {@code SIZED} and {@code SUBSIZED}.  The caller may provide additional
     * characteristics for the spliterator to report.  (For example, if it is
     * known the array will not be further modified, specify {@code IMMUTABLE};
     * if the array data is considered to have an an encounter order, specify
     * {@code ORDERED}).  The method {@link Arrays#spliterator(long[], int, int)} can
     * often be used instead, which returns a spliterator that reports
     * {@code SIZED}, {@code SUBSIZED}, {@code IMMUTABLE}, and {@code ORDERED}.
     *
     * @param array The array, assumed to be unmodified during use
     * @param fromIndex The least index (inclusive) to cover
     * @param toIndex One past the greatest index to cover
     * @param additionalCharacteristics Additional spliterator characteristics
     *        of this spliterator's source or elements beyond {@code SIZED} and
     *        {@code SUBSIZED} which are are always reported
     * @return A spliterator for an array
     * @throws NullPointerException if the given array is {@code null}
     * @throws ArrayIndexOutOfBoundsException if {@code fromIndex} is negative,
     *         {@code toIndex} is less than {@code fromIndex}, or
     *         {@code toIndex} is greater than the array size
     * @see Arrays#spliterator(double[], int, int)
     */
    public static Spliterator.OfDouble spliterator(double[] array, int fromIndex, int toIndex,
                                                   int additionalCharacteristics) {
        checkFromToBounds(Objects.requireNonNull(array).length, fromIndex, toIndex);
        return new DoubleArraySpliterator(array, fromIndex, toIndex, additionalCharacteristics);
    }

    /**
     * Validate inclusive start index and exclusive end index against the length
     * of an array.
     * @param arrayLength The length of the array
     * @param origin The inclusive start index
     * @param fence The exclusive end index
     * @throws ArrayIndexOutOfBoundsException if the start index is greater than
     * the end index, if the start index is negative, or the end index is
     * greater than the array length
     */
    private static void checkFromToBounds(int arrayLength, int origin, int fence) {
        if (origin > fence) {
            throw new ArrayIndexOutOfBoundsException(
                    "origin(" + origin + ") > fence(" + fence + ")");
        }
        if (origin < 0) {
            throw new ArrayIndexOutOfBoundsException(origin);
        }
        if (fence > arrayLength) {
            throw new ArrayIndexOutOfBoundsException(fence);
        }
    }

    // Iterator-based spliterators

    /**
     * Creates a {@code Spliterator} using the given collection's
     * {@link java.util.Collection#iterator()} as the source of elements, and
     * reporting its {@link java.util.Collection#size()} as its initial size.
     *
     * <p>The spliterator is
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the collection's iterator, and
     * implements {@code trySplit} to permit limited parallelism.
     *
     * @param <T> Type of elements
     * @param c The collection
     * @param characteristics Characteristics of this spliterator's source or
     *        elements.  The characteristics {@code SIZED} and {@code SUBSIZED}
     *        are additionally reported unless {@code CONCURRENT} is supplied.
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given collection is {@code null}
     * 基于 collection 生成的分裂迭代器对象
     */
    public static <T> Spliterator<T> spliterator(Collection<? extends T> c,
                                                 int characteristics) {
        return new IteratorSpliterator<>(Objects.requireNonNull(c),
                                         characteristics);
    }

    /**
     * Creates a {@code Spliterator} using a given {@code Iterator}
     * as the source of elements, and with a given initially reported size.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned, or the initially reported
     * size is not equal to the actual number of elements in the source.
     *
     * @param <T> Type of elements
     * @param iterator The iterator for the source
     * @param size The number of elements in the source, to be reported as
     *        initial {@code estimateSize}
     * @param characteristics Characteristics of this spliterator's source or
     *        elements.  The characteristics {@code SIZED} and {@code SUBSIZED}
     *        are additionally reported unless {@code CONCURRENT} is supplied.
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     */
    public static <T> Spliterator<T> spliterator(Iterator<? extends T> iterator,
                                                 long size,
                                                 int characteristics) {
        return new IteratorSpliterator<>(Objects.requireNonNull(iterator), size,
                                         characteristics);
    }

    /**
     * Creates a {@code Spliterator} using a given {@code Iterator}
     * as the source of elements, with no initial size estimate.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned.
     *
     * @param <T> Type of elements
     * @param iterator The iterator for the source
     * @param characteristics Characteristics of this spliterator's source
     *        or elements ({@code SIZED} and {@code SUBSIZED}, if supplied, are
     *        ignored and are not reported.)
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     */
    public static <T> Spliterator<T> spliteratorUnknownSize(Iterator<? extends T> iterator,
                                                            int characteristics) {
        return new IteratorSpliterator<>(Objects.requireNonNull(iterator), characteristics);
    }

    /**
     * Creates a {@code Spliterator.OfInt} using a given
     * {@code IntStream.IntIterator} as the source of elements, and with a given
     * initially reported size.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned, or the initially reported
     * size is not equal to the actual number of elements in the source.
     *
     * @param iterator The iterator for the source
     * @param size The number of elements in the source, to be reported as
     *        initial {@code estimateSize}.
     * @param characteristics Characteristics of this spliterator's source or
     *        elements.  The characteristics {@code SIZED} and {@code SUBSIZED}
     *        are additionally reported unless {@code CONCURRENT} is supplied.
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     * 使用原始类型 迭代器进行初始化
     */
    public static Spliterator.OfInt spliterator(PrimitiveIterator.OfInt iterator,
                                                long size,
                                                int characteristics) {
        return new IntIteratorSpliterator(Objects.requireNonNull(iterator),
                                          size, characteristics);
    }

    /**
     * Creates a {@code Spliterator.OfInt} using a given
     * {@code IntStream.IntIterator} as the source of elements, with no initial
     * size estimate.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned.
     *
     * @param iterator The iterator for the source
     * @param characteristics Characteristics of this spliterator's source
     *        or elements ({@code SIZED} and {@code SUBSIZED}, if supplied, are
     *        ignored and are not reported.)
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     */
    public static Spliterator.OfInt spliteratorUnknownSize(PrimitiveIterator.OfInt iterator,
                                                           int characteristics) {
        return new IntIteratorSpliterator(Objects.requireNonNull(iterator), characteristics);
    }

    /**
     * Creates a {@code Spliterator.OfLong} using a given
     * {@code LongStream.LongIterator} as the source of elements, and with a
     * given initially reported size.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned, or the initially reported
     * size is not equal to the actual number of elements in the source.
     *
     * @param iterator The iterator for the source
     * @param size The number of elements in the source, to be reported as
     *        initial {@code estimateSize}.
     * @param characteristics Characteristics of this spliterator's source or
     *        elements.  The characteristics {@code SIZED} and {@code SUBSIZED}
     *        are additionally reported unless {@code CONCURRENT} is supplied.
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     */
    public static Spliterator.OfLong spliterator(PrimitiveIterator.OfLong iterator,
                                                 long size,
                                                 int characteristics) {
        return new LongIteratorSpliterator(Objects.requireNonNull(iterator),
                                           size, characteristics);
    }

    /**
     * Creates a {@code Spliterator.OfLong} using a given
     * {@code LongStream.LongIterator} as the source of elements, with no
     * initial size estimate.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned.
     *
     * @param iterator The iterator for the source
     * @param characteristics Characteristics of this spliterator's source
     *        or elements ({@code SIZED} and {@code SUBSIZED}, if supplied, are
     *        ignored and are not reported.)
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     */
    public static Spliterator.OfLong spliteratorUnknownSize(PrimitiveIterator.OfLong iterator,
                                                            int characteristics) {
        return new LongIteratorSpliterator(Objects.requireNonNull(iterator), characteristics);
    }

    /**
     * Creates a {@code Spliterator.OfDouble} using a given
     * {@code DoubleStream.DoubleIterator} as the source of elements, and with a
     * given initially reported size.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned, or the initially reported
     * size is not equal to the actual number of elements in the source.
     *
     * @param iterator The iterator for the source
     * @param size The number of elements in the source, to be reported as
     *        initial {@code estimateSize}
     * @param characteristics Characteristics of this spliterator's source or
     *        elements.  The characteristics {@code SIZED} and {@code SUBSIZED}
     *        are additionally reported unless {@code CONCURRENT} is supplied.
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     */
    public static Spliterator.OfDouble spliterator(PrimitiveIterator.OfDouble iterator,
                                                   long size,
                                                   int characteristics) {
        return new DoubleIteratorSpliterator(Objects.requireNonNull(iterator),
                                             size, characteristics);
    }

    /**
     * Creates a {@code Spliterator.OfDouble} using a given
     * {@code DoubleStream.DoubleIterator} as the source of elements, with no
     * initial size estimate.
     *
     * <p>The spliterator is not
     * <em><a href="Spliterator.html#binding">late-binding</a></em>, inherits
     * the <em>fail-fast</em> properties of the iterator, and implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>Traversal of elements should be accomplished through the spliterator.
     * The behaviour of splitting and traversal is undefined if the iterator is
     * operated on after the spliterator is returned.
     *
     * @param iterator The iterator for the source
     * @param characteristics Characteristics of this spliterator's source
     *        or elements ({@code SIZED} and {@code SUBSIZED}, if supplied, are
     *        ignored and are not reported.)
     * @return A spliterator from an iterator
     * @throws NullPointerException if the given iterator is {@code null}
     */
    public static Spliterator.OfDouble spliteratorUnknownSize(PrimitiveIterator.OfDouble iterator,
                                                              int characteristics) {
        return new DoubleIteratorSpliterator(Objects.requireNonNull(iterator), characteristics);
    }

    // Iterators from Spliterators

    /**
     * Creates an {@code Iterator} from a {@code Spliterator}.
     *
     * <p>Traversal of elements should be accomplished through the iterator.
     * The behaviour of traversal is undefined if the spliterator is operated
     * after the iterator is returned.
     *
     * @param <T> Type of elements
     * @param spliterator The spliterator
     * @return An iterator
     * @throws NullPointerException if the given spliterator is {@code null}
     * 将 可拆分迭代器对象 变成一个普通的迭代器对象
     */
    public static<T> Iterator<T> iterator(Spliterator<? extends T> spliterator) {
        Objects.requireNonNull(spliterator);
        // 返回的 适配器对象可以从 split中 剥离数据并生成新的迭代器
        class Adapter implements Iterator<T>, Consumer<T> {
            boolean valueReady = false;
            T nextElement;

            @Override
            public void accept(T t) {
                valueReady = true;
                nextElement = t;
            }

            @Override
            public boolean hasNext() {
                if (!valueReady)
                    spliterator.tryAdvance(this);
                return valueReady;
            }

            @Override
            public T next() {
                if (!valueReady && !hasNext())
                    throw new NoSuchElementException();
                else {
                    valueReady = false;
                    return nextElement;
                }
            }
        }

        return new Adapter();
    }

    /**
     * Creates an {@code PrimitiveIterator.OfInt} from a
     * {@code Spliterator.OfInt}.
     *
     * <p>Traversal of elements should be accomplished through the iterator.
     * The behaviour of traversal is undefined if the spliterator is operated
     * after the iterator is returned.
     *
     * @param spliterator The spliterator
     * @return An iterator
     * @throws NullPointerException if the given spliterator is {@code null}
     */
    public static PrimitiveIterator.OfInt iterator(Spliterator.OfInt spliterator) {
        Objects.requireNonNull(spliterator);
        class Adapter implements PrimitiveIterator.OfInt, IntConsumer {
            boolean valueReady = false;
            int nextElement;

            @Override
            public void accept(int t) {
                valueReady = true;
                nextElement = t;
            }

            @Override
            public boolean hasNext() {
                if (!valueReady)
                    spliterator.tryAdvance(this);
                return valueReady;
            }

            @Override
            public int nextInt() {
                if (!valueReady && !hasNext())
                    throw new NoSuchElementException();
                else {
                    valueReady = false;
                    return nextElement;
                }
            }
        }

        return new Adapter();
    }

    /**
     * Creates an {@code PrimitiveIterator.OfLong} from a
     * {@code Spliterator.OfLong}.
     *
     * <p>Traversal of elements should be accomplished through the iterator.
     * The behaviour of traversal is undefined if the spliterator is operated
     * after the iterator is returned.
     *
     * @param spliterator The spliterator
     * @return An iterator
     * @throws NullPointerException if the given spliterator is {@code null}
     */
    public static PrimitiveIterator.OfLong iterator(Spliterator.OfLong spliterator) {
        Objects.requireNonNull(spliterator);
        class Adapter implements PrimitiveIterator.OfLong, LongConsumer {
            boolean valueReady = false;
            long nextElement;

            @Override
            public void accept(long t) {
                valueReady = true;
                nextElement = t;
            }

            @Override
            public boolean hasNext() {
                if (!valueReady)
                    spliterator.tryAdvance(this);
                return valueReady;
            }

            @Override
            public long nextLong() {
                if (!valueReady && !hasNext())
                    throw new NoSuchElementException();
                else {
                    valueReady = false;
                    return nextElement;
                }
            }
        }

        return new Adapter();
    }

    /**
     * Creates an {@code PrimitiveIterator.OfDouble} from a
     * {@code Spliterator.OfDouble}.
     *
     * <p>Traversal of elements should be accomplished through the iterator.
     * The behaviour of traversal is undefined if the spliterator is operated
     * after the iterator is returned.
     *
     * @param spliterator The spliterator
     * @return An iterator
     * @throws NullPointerException if the given spliterator is {@code null}
     */
    public static PrimitiveIterator.OfDouble iterator(Spliterator.OfDouble spliterator) {
        Objects.requireNonNull(spliterator);
        class Adapter implements PrimitiveIterator.OfDouble, DoubleConsumer {
            boolean valueReady = false;
            double nextElement;

            @Override
            public void accept(double t) {
                valueReady = true;
                nextElement = t;
            }

            @Override
            public boolean hasNext() {
                if (!valueReady)
                    spliterator.tryAdvance(this);
                return valueReady;
            }

            @Override
            public double nextDouble() {
                if (!valueReady && !hasNext())
                    throw new NoSuchElementException();
                else {
                    valueReady = false;
                    return nextElement;
                }
            }
        }

        return new Adapter();
    }

    // Implementations

    /**
     * 代表一个空的并行迭代器对象
     * @param <T>
     * @param <S>
     * @param <C>
     */
    private static abstract class EmptySpliterator<T, S extends Spliterator<T>, C> {

        EmptySpliterator() { }

        /**
         * 代表无法被拆分
         * @return
         */
        public S trySplit() {
            return null;
        }

        public boolean tryAdvance(C consumer) {
            Objects.requireNonNull(consumer);
            return false;
        }

        /**
         * 只是单纯校验 cons 不为null 因为内部没有其他元素 所以不做处理
         * @param consumer
         */
        public void forEachRemaining(C consumer) {
            Objects.requireNonNull(consumer);
        }

        public long estimateSize() {
            return 0;
        }

        public int characteristics() {
            return Spliterator.SIZED | Spliterator.SUBSIZED;
        }

        private static final class OfRef<T>
                extends EmptySpliterator<T, Spliterator<T>, Consumer<? super T>>
                implements Spliterator<T> {
            OfRef() { }
        }

        private static final class OfInt
                extends EmptySpliterator<Integer, Spliterator.OfInt, IntConsumer>
                implements Spliterator.OfInt {
            OfInt() { }
        }

        private static final class OfLong
                extends EmptySpliterator<Long, Spliterator.OfLong, LongConsumer>
                implements Spliterator.OfLong {
            OfLong() { }
        }

        private static final class OfDouble
                extends EmptySpliterator<Double, Spliterator.OfDouble, DoubleConsumer>
                implements Spliterator.OfDouble {
            OfDouble() { }
        }
    }

    // Array-based spliterators

    /**
     * A Spliterator designed for use by sources that traverse and split
     * elements maintained in an unmodifiable {@code Object[]} array.
     * 基于数组实现的 可拆分迭代器对象
     */
    static final class ArraySpliterator<T> implements Spliterator<T> {
        /**
         * The array, explicitly typed as Object[]. Unlike in some other
         * classes (see for example CR 6260652), we do not need to
         * screen arguments to ensure they are exactly of type Object[]
         * so long as no methods write into the array or serialize it,
         * which we ensure here by defining this class as final.
         */
        private final Object[] array;
        // 该属性会在 调用 advance/split 时发生改变
        private int index;        // current index, modified on advance/split
        // 最后一个索引
        private final int fence;  // one past last index
        // 特性
        private final int characteristics;

        /**
         * Creates a spliterator covering all of the given array.
         * @param array the array, assumed to be unmodified during use
         * @param additionalCharacteristics Additional spliterator characteristics
         * of this spliterator's source or elements beyond {@code SIZED} and
         * {@code SUBSIZED} which are are always reported
         */
        public ArraySpliterator(Object[] array, int additionalCharacteristics) {
            this(array, 0, array.length, additionalCharacteristics);
        }

        /**
         * Creates a spliterator covering the given array and range
         * @param array the array, assumed to be unmodified during use
         * @param origin the least index (inclusive) to cover
         * @param fence one past the greatest index to cover
         * @param additionalCharacteristics Additional spliterator characteristics
         * of this spliterator's source or elements beyond {@code SIZED} and
         * {@code SUBSIZED} which are are always reported
         */
        public ArraySpliterator(Object[] array, int origin, int fence, int additionalCharacteristics) {
            this.array = array;
            this.index = origin;
            this.fence = fence;
            // 看来特性总是会追加 SIZED | SUBSIZED
            this.characteristics = additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED;
        }

        /**
         * 将当前 spliterator 分为2半  注意使用的是同一个array  调用该方法后 原对象变成了后半部分 （指针分别指向中间和尾部）
         * @return
         */
        @Override
        public Spliterator<T> trySplit() {
            // 这里获取了 中间的下标 ???
            int lo = index, mid = (lo + fence) >>> 1;
            return (lo >= mid)
                   ? null
                    // 返回前半部分
                   : new ArraySpliterator<>(array, lo, index = mid, characteristics);
        }

        /**
         * 就是普通的迭代
         * @param action The action
         */
        @SuppressWarnings("unchecked")
        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            Object[] a; int i, hi; // hoist accesses and checks from loop
            if (action == null)
                throw new NullPointerException();
            if ((a = array).length >= (hi = fence) &&
                (i = index) >= 0 && i < (index = hi)) {
                do { action.accept((T)a[i]); } while (++i < hi);
            }
        }

        /**
         * 移动下标
         * @param action The action
         * @return
         */
        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            if (action == null)
                throw new NullPointerException();
            if (index >= 0 && index < fence) {
                @SuppressWarnings("unchecked") T e = (T) array[index++];
                action.accept(e);
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() { return (long)(fence - index); }

        @Override
        public int characteristics() {
            return characteristics;
        }

        @Override
        public Comparator<? super T> getComparator() {
            // 如果包含 sorted 返回null ?
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }

    /**
     * A Spliterator.OfInt designed for use by sources that traverse and split
     * elements maintained in an unmodifiable {@code int[]} array.
     */
    static final class IntArraySpliterator implements Spliterator.OfInt {
        private final int[] array;
        private int index;        // current index, modified on advance/split
        private final int fence;  // one past last index
        private final int characteristics;

        /**
         * Creates a spliterator covering all of the given array.
         * @param array the array, assumed to be unmodified during use
         * @param additionalCharacteristics Additional spliterator characteristics
         *        of this spliterator's source or elements beyond {@code SIZED} and
         *        {@code SUBSIZED} which are are always reported
         */
        public IntArraySpliterator(int[] array, int additionalCharacteristics) {
            this(array, 0, array.length, additionalCharacteristics);
        }

        /**
         * Creates a spliterator covering the given array and range
         * @param array the array, assumed to be unmodified during use
         * @param origin the least index (inclusive) to cover
         * @param fence one past the greatest index to cover
         * @param additionalCharacteristics Additional spliterator characteristics
         *        of this spliterator's source or elements beyond {@code SIZED} and
         *        {@code SUBSIZED} which are are always reported
         */
        public IntArraySpliterator(int[] array, int origin, int fence, int additionalCharacteristics) {
            this.array = array;
            this.index = origin;
            this.fence = fence;
            this.characteristics = additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED;
        }

        @Override
        public OfInt trySplit() {
            int lo = index, mid = (lo + fence) >>> 1;
            return (lo >= mid)
                   ? null
                   : new IntArraySpliterator(array, lo, index = mid, characteristics);
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            int[] a; int i, hi; // hoist accesses and checks from loop
            if (action == null)
                throw new NullPointerException();
            if ((a = array).length >= (hi = fence) &&
                (i = index) >= 0 && i < (index = hi)) {
                do { action.accept(a[i]); } while (++i < hi);
            }
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            if (action == null)
                throw new NullPointerException();
            if (index >= 0 && index < fence) {
                action.accept(array[index++]);
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() { return (long)(fence - index); }

        @Override
        public int characteristics() {
            return characteristics;
        }

        @Override
        public Comparator<? super Integer> getComparator() {
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }

    /**
     * A Spliterator.OfLong designed for use by sources that traverse and split
     * elements maintained in an unmodifiable {@code int[]} array.
     */
    static final class LongArraySpliterator implements Spliterator.OfLong {
        private final long[] array;
        private int index;        // current index, modified on advance/split
        private final int fence;  // one past last index
        private final int characteristics;

        /**
         * Creates a spliterator covering all of the given array.
         * @param array the array, assumed to be unmodified during use
         * @param additionalCharacteristics Additional spliterator characteristics
         *        of this spliterator's source or elements beyond {@code SIZED} and
         *        {@code SUBSIZED} which are are always reported
         */
        public LongArraySpliterator(long[] array, int additionalCharacteristics) {
            this(array, 0, array.length, additionalCharacteristics);
        }

        /**
         * Creates a spliterator covering the given array and range
         * @param array the array, assumed to be unmodified during use
         * @param origin the least index (inclusive) to cover
         * @param fence one past the greatest index to cover
         * @param additionalCharacteristics Additional spliterator characteristics
         *        of this spliterator's source or elements beyond {@code SIZED} and
         *        {@code SUBSIZED} which are are always reported
         */
        public LongArraySpliterator(long[] array, int origin, int fence, int additionalCharacteristics) {
            this.array = array;
            this.index = origin;
            this.fence = fence;
            this.characteristics = additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED;
        }

        @Override
        public OfLong trySplit() {
            int lo = index, mid = (lo + fence) >>> 1;
            return (lo >= mid)
                   ? null
                   : new LongArraySpliterator(array, lo, index = mid, characteristics);
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            long[] a; int i, hi; // hoist accesses and checks from loop
            if (action == null)
                throw new NullPointerException();
            if ((a = array).length >= (hi = fence) &&
                (i = index) >= 0 && i < (index = hi)) {
                do { action.accept(a[i]); } while (++i < hi);
            }
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            if (action == null)
                throw new NullPointerException();
            if (index >= 0 && index < fence) {
                action.accept(array[index++]);
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() { return (long)(fence - index); }

        @Override
        public int characteristics() {
            return characteristics;
        }

        @Override
        public Comparator<? super Long> getComparator() {
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }

    /**
     * A Spliterator.OfDouble designed for use by sources that traverse and split
     * elements maintained in an unmodifiable {@code int[]} array.
     */
    static final class DoubleArraySpliterator implements Spliterator.OfDouble {
        private final double[] array;
        private int index;        // current index, modified on advance/split
        private final int fence;  // one past last index
        private final int characteristics;

        /**
         * Creates a spliterator covering all of the given array.
         * @param array the array, assumed to be unmodified during use
         * @param additionalCharacteristics Additional spliterator characteristics
         *        of this spliterator's source or elements beyond {@code SIZED} and
         *        {@code SUBSIZED} which are are always reported
         */
        public DoubleArraySpliterator(double[] array, int additionalCharacteristics) {
            this(array, 0, array.length, additionalCharacteristics);
        }

        /**
         * Creates a spliterator covering the given array and range
         * @param array the array, assumed to be unmodified during use
         * @param origin the least index (inclusive) to cover
         * @param fence one past the greatest index to cover
         * @param additionalCharacteristics Additional spliterator characteristics
         *        of this spliterator's source or elements beyond {@code SIZED} and
         *        {@code SUBSIZED} which are are always reported
         */
        public DoubleArraySpliterator(double[] array, int origin, int fence, int additionalCharacteristics) {
            this.array = array;
            this.index = origin;
            this.fence = fence;
            this.characteristics = additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED;
        }

        @Override
        public OfDouble trySplit() {
            int lo = index, mid = (lo + fence) >>> 1;
            return (lo >= mid)
                   ? null
                   : new DoubleArraySpliterator(array, lo, index = mid, characteristics);
        }

        @Override
        public void forEachRemaining(DoubleConsumer action) {
            double[] a; int i, hi; // hoist accesses and checks from loop
            if (action == null)
                throw new NullPointerException();
            if ((a = array).length >= (hi = fence) &&
                (i = index) >= 0 && i < (index = hi)) {
                do { action.accept(a[i]); } while (++i < hi);
            }
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            if (action == null)
                throw new NullPointerException();
            if (index >= 0 && index < fence) {
                action.accept(array[index++]);
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() { return (long)(fence - index); }

        @Override
        public int characteristics() {
            return characteristics;
        }

        @Override
        public Comparator<? super Double> getComparator() {
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }

    //

    /**
     * An abstract {@code Spliterator} that implements {@code trySplit} to
     * permit limited parallelism.
     *
     * <p>An extending class need only
     * implement {@link #tryAdvance(java.util.function.Consumer) tryAdvance}.
     * The extending class should override
     * {@link #forEachRemaining(java.util.function.Consumer) forEach} if it can
     * provide a more performant implementation.
     *
     * @apiNote
     * This class is a useful aid for creating a spliterator when it is not
     * possible or difficult to efficiently partition elements in a manner
     * allowing balanced parallel computation.
     *
     * <p>An alternative to using this class, that also permits limited
     * parallelism, is to create a spliterator from an iterator
     * (see {@link #spliterator(Iterator, long, int)}.  Depending on the
     * circumstances using an iterator may be easier or more convenient than
     * extending this class, such as when there is already an iterator
     * available to use.
     *
     * @see #spliterator(Iterator, long, int)
     * @since 1.8
     * 可拆分迭代器的骨架类  内部没有存放实际元素的对象
     */
    public static abstract class AbstractSpliterator<T> implements Spliterator<T> {
        static final int BATCH_UNIT = 1 << 10;  // batch array size increment
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        private final int characteristics;
        private long est;             // size estimate
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator reporting the given estimated size and
         * additionalCharacteristics.
         *
         * @param est the estimated size of this spliterator if known, otherwise
         *        {@code Long.MAX_VALUE}.
         * @param additionalCharacteristics properties of this spliterator's
         *        source or elements.  If {@code SIZED} is reported then this
         *        spliterator will additionally report {@code SUBSIZED}.
         */
        protected AbstractSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.characteristics = ((additionalCharacteristics & Spliterator.SIZED) != 0)
                                   ? additionalCharacteristics | Spliterator.SUBSIZED
                                   : additionalCharacteristics;
        }

        static final class HoldingConsumer<T> implements Consumer<T> {
            Object value;

            @Override
            public void accept(T value) {
                this.value = value;
            }
        }

        /**
         * {@inheritDoc}
         *
         * This implementation permits limited parallelism.
         */
        @Override
        public Spliterator<T> trySplit() {
            /*
             * Split into arrays of arithmetically increasing batch
             * sizes.  This will only improve parallel performance if
             * per-element Consumer actions are more costly than
             * transferring them into an array.  The use of an
             * arithmetic progression in split sizes provides overhead
             * vs parallelism bounds that do not particularly favor or
             * penalize cases of lightweight vs heavyweight element
             * operations, across combinations of #elements vs #cores,
             * whether or not either are known.  We generate
             * O(sqrt(#elements)) splits, allowing O(sqrt(#cores))
             * potential speedup.
             */
            HoldingConsumer<T> holder = new HoldingConsumer<>();
            long s = est;
            if (s > 1 && tryAdvance(holder)) {
                int n = batch + BATCH_UNIT;
                if (n > s)
                    n = (int) s;
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                Object[] a = new Object[n];
                int j = 0;
                do { a[j] = holder.value; } while (++j < n && tryAdvance(holder));
                batch = j;
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new ArraySpliterator<>(a, 0, j, characteristics());
            }
            return null;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the estimated size as reported when
         * created and, if the estimate size is known, decreases in size when
         * split.
         */
        @Override
        public long estimateSize() {
            return est;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the characteristics as reported when
         * created.
         */
        @Override
        public int characteristics() {
            return characteristics;
        }
    }

    /**
     * An abstract {@code Spliterator.OfInt} that implements {@code trySplit} to
     * permit limited parallelism.
     *
     * <p>To implement a spliterator an extending class need only
     * implement {@link #tryAdvance(java.util.function.IntConsumer)}
     * tryAdvance}.  The extending class should override
     * {@link #forEachRemaining(java.util.function.IntConsumer)} forEach} if it
     * can provide a more performant implementation.
     *
     * @apiNote
     * This class is a useful aid for creating a spliterator when it is not
     * possible or difficult to efficiently partition elements in a manner
     * allowing balanced parallel computation.
     *
     * <p>An alternative to using this class, that also permits limited
     * parallelism, is to create a spliterator from an iterator
     * (see {@link #spliterator(java.util.PrimitiveIterator.OfInt, long, int)}.
     * Depending on the circumstances using an iterator may be easier or more
     * convenient than extending this class. For example, if there is already an
     * iterator available to use then there is no need to extend this class.
     *
     * @see #spliterator(java.util.PrimitiveIterator.OfInt, long, int)
     * @since 1.8
     */
    public static abstract class AbstractIntSpliterator implements Spliterator.OfInt {
        static final int MAX_BATCH = AbstractSpliterator.MAX_BATCH;
        static final int BATCH_UNIT = AbstractSpliterator.BATCH_UNIT;
        private final int characteristics;
        private long est;             // size estimate
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator reporting the given estimated size and
         * characteristics.
         *
         * @param est the estimated size of this spliterator if known, otherwise
         *        {@code Long.MAX_VALUE}.
         * @param additionalCharacteristics properties of this spliterator's
         *        source or elements.  If {@code SIZED} is reported then this
         *        spliterator will additionally report {@code SUBSIZED}.
         */
        protected AbstractIntSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.characteristics = ((additionalCharacteristics & Spliterator.SIZED) != 0)
                                   ? additionalCharacteristics | Spliterator.SUBSIZED
                                   : additionalCharacteristics;
        }

        static final class HoldingIntConsumer implements IntConsumer {
            int value;

            @Override
            public void accept(int value) {
                this.value = value;
            }
        }

        /**
         * {@inheritDoc}
         *
         * This implementation permits limited parallelism.
         */
        @Override
        public Spliterator.OfInt trySplit() {
            HoldingIntConsumer holder = new HoldingIntConsumer();
            long s = est;
            if (s > 1 && tryAdvance(holder)) {
                int n = batch + BATCH_UNIT;
                if (n > s)
                    n = (int) s;
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                int[] a = new int[n];
                int j = 0;
                do { a[j] = holder.value; } while (++j < n && tryAdvance(holder));
                batch = j;
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new IntArraySpliterator(a, 0, j, characteristics());
            }
            return null;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the estimated size as reported when
         * created and, if the estimate size is known, decreases in size when
         * split.
         */
        @Override
        public long estimateSize() {
            return est;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the characteristics as reported when
         * created.
         */
        @Override
        public int characteristics() {
            return characteristics;
        }
    }

    /**
     * An abstract {@code Spliterator.OfLong} that implements {@code trySplit}
     * to permit limited parallelism.
     *
     * <p>To implement a spliterator an extending class need only
     * implement {@link #tryAdvance(java.util.function.LongConsumer)}
     * tryAdvance}.  The extending class should override
     * {@link #forEachRemaining(java.util.function.LongConsumer)} forEach} if it
     * can provide a more performant implementation.
     *
     * @apiNote
     * This class is a useful aid for creating a spliterator when it is not
     * possible or difficult to efficiently partition elements in a manner
     * allowing balanced parallel computation.
     *
     * <p>An alternative to using this class, that also permits limited
     * parallelism, is to create a spliterator from an iterator
     * (see {@link #spliterator(java.util.PrimitiveIterator.OfLong, long, int)}.
     * Depending on the circumstances using an iterator may be easier or more
     * convenient than extending this class. For example, if there is already an
     * iterator available to use then there is no need to extend this class.
     *
     * @see #spliterator(java.util.PrimitiveIterator.OfLong, long, int)
     * @since 1.8
     */
    public static abstract class AbstractLongSpliterator implements Spliterator.OfLong {
        static final int MAX_BATCH = AbstractSpliterator.MAX_BATCH;
        static final int BATCH_UNIT = AbstractSpliterator.BATCH_UNIT;
        private final int characteristics;
        private long est;             // size estimate
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator reporting the given estimated size and
         * characteristics.
         *
         * @param est the estimated size of this spliterator if known, otherwise
         *        {@code Long.MAX_VALUE}.
         * @param additionalCharacteristics properties of this spliterator's
         *        source or elements.  If {@code SIZED} is reported then this
         *        spliterator will additionally report {@code SUBSIZED}.
         */
        protected AbstractLongSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.characteristics = ((additionalCharacteristics & Spliterator.SIZED) != 0)
                                   ? additionalCharacteristics | Spliterator.SUBSIZED
                                   : additionalCharacteristics;
        }

        static final class HoldingLongConsumer implements LongConsumer {
            long value;

            @Override
            public void accept(long value) {
                this.value = value;
            }
        }

        /**
         * {@inheritDoc}
         *
         * This implementation permits limited parallelism.
         */
        @Override
        public Spliterator.OfLong trySplit() {
            HoldingLongConsumer holder = new HoldingLongConsumer();
            long s = est;
            if (s > 1 && tryAdvance(holder)) {
                int n = batch + BATCH_UNIT;
                if (n > s)
                    n = (int) s;
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                long[] a = new long[n];
                int j = 0;
                do { a[j] = holder.value; } while (++j < n && tryAdvance(holder));
                batch = j;
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new LongArraySpliterator(a, 0, j, characteristics());
            }
            return null;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the estimated size as reported when
         * created and, if the estimate size is known, decreases in size when
         * split.
         */
        @Override
        public long estimateSize() {
            return est;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the characteristics as reported when
         * created.
         */
        @Override
        public int characteristics() {
            return characteristics;
        }
    }

    /**
     * An abstract {@code Spliterator.OfDouble} that implements
     * {@code trySplit} to permit limited parallelism.
     *
     * <p>To implement a spliterator an extending class need only
     * implement {@link #tryAdvance(java.util.function.DoubleConsumer)}
     * tryAdvance}.  The extending class should override
     * {@link #forEachRemaining(java.util.function.DoubleConsumer)} forEach} if
     * it can provide a more performant implementation.
     *
     * @apiNote
     * This class is a useful aid for creating a spliterator when it is not
     * possible or difficult to efficiently partition elements in a manner
     * allowing balanced parallel computation.
     *
     * <p>An alternative to using this class, that also permits limited
     * parallelism, is to create a spliterator from an iterator
     * (see {@link #spliterator(java.util.PrimitiveIterator.OfDouble, long, int)}.
     * Depending on the circumstances using an iterator may be easier or more
     * convenient than extending this class. For example, if there is already an
     * iterator available to use then there is no need to extend this class.
     *
     * @see #spliterator(java.util.PrimitiveIterator.OfDouble, long, int)
     * @since 1.8
     */
    public static abstract class AbstractDoubleSpliterator implements Spliterator.OfDouble {
        static final int MAX_BATCH = AbstractSpliterator.MAX_BATCH;
        static final int BATCH_UNIT = AbstractSpliterator.BATCH_UNIT;
        private final int characteristics;
        private long est;             // size estimate
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator reporting the given estimated size and
         * characteristics.
         *
         * @param est the estimated size of this spliterator if known, otherwise
         *        {@code Long.MAX_VALUE}.
         * @param additionalCharacteristics properties of this spliterator's
         *        source or elements.  If {@code SIZED} is reported then this
         *        spliterator will additionally report {@code SUBSIZED}.
         */
        protected AbstractDoubleSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.characteristics = ((additionalCharacteristics & Spliterator.SIZED) != 0)
                                   ? additionalCharacteristics | Spliterator.SUBSIZED
                                   : additionalCharacteristics;
        }

        static final class HoldingDoubleConsumer implements DoubleConsumer {
            double value;

            @Override
            public void accept(double value) {
                this.value = value;
            }
        }

        /**
         * {@inheritDoc}
         *
         * This implementation permits limited parallelism.
         */
        @Override
        public Spliterator.OfDouble trySplit() {
            HoldingDoubleConsumer holder = new HoldingDoubleConsumer();
            long s = est;
            if (s > 1 && tryAdvance(holder)) {
                int n = batch + BATCH_UNIT;
                if (n > s)
                    n = (int) s;
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                double[] a = new double[n];
                int j = 0;
                do { a[j] = holder.value; } while (++j < n && tryAdvance(holder));
                batch = j;
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new DoubleArraySpliterator(a, 0, j, characteristics());
            }
            return null;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the estimated size as reported when
         * created and, if the estimate size is known, decreases in size when
         * split.
         */
        @Override
        public long estimateSize() {
            return est;
        }

        /**
         * {@inheritDoc}
         *
         * @implSpec
         * This implementation returns the characteristics as reported when
         * created.
         */
        @Override
        public int characteristics() {
            return characteristics;
        }
    }

    // Iterator-based Spliterators

    /**
     * A Spliterator using a given Iterator for element
     * operations. The spliterator implements {@code trySplit} to
     * permit limited parallelism.
     * 内部基于可迭代对象的 拆分迭代器
     */
    static class IteratorSpliterator<T> implements Spliterator<T> {
        /**
         * 1024  代表拆分出一个 分片的最大单位
         */
        static final int BATCH_UNIT = 1 << 10;  // batch array size increment
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        private final Collection<? extends T> collection; // null OK
        private Iterator<? extends T> it;
        private final int characteristics;
        /**
         * 代表元素长度
         */
        private long est;             // size estimate
        /**
         * 代表上次 为了分裂已经使用了多少大小 默认为0 每次会分配至多BATCH_UNIT 的大小， 不允许超过MAX_BATCH
         */
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator using the given given
         * collection's {@link java.util.Collection#iterator()) for traversal,
         * and reporting its {@link java.util.Collection#size()) as its initial
         * size.
         *
         * @param c the collection
         * @param characteristics properties of this spliterator's
         *        source or elements.
         */
        public IteratorSpliterator(Collection<? extends T> collection, int characteristics) {
            this.collection = collection;
            this.it = null;
            // characteristics 可以设置是否是并发安全的
            this.characteristics = (characteristics & Spliterator.CONCURRENT) == 0
                                    // 非并发安全要追加这2个属性
                                   ? characteristics | Spliterator.SIZED | Spliterator.SUBSIZED
                                   : characteristics;
        }

        /**
         * Creates a spliterator using the given iterator
         * for traversal, and reporting the given initial size
         * and characteristics.
         *
         * @param iterator the iterator for the source
         * @param size the number of elements in the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         *                        当使用迭代器进行初始化时  est 信息可以自由设定 默认为 Long.Max_Value
         */
        public IteratorSpliterator(Iterator<? extends T> iterator, long size, int characteristics) {
            this.collection = null;
            this.it = iterator;
            this.est = size;
            this.characteristics = (characteristics & Spliterator.CONCURRENT) == 0
                                   ? characteristics | Spliterator.SIZED | Spliterator.SUBSIZED
                                   : characteristics;
        }

        /**
         * Creates a spliterator using the given iterator
         * for traversal, and reporting the given initial size
         * and characteristics.
         *
         * @param iterator the iterator for the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         */
        public IteratorSpliterator(Iterator<? extends T> iterator, int characteristics) {
            this.collection = null;
            this.it = iterator;
            // 默认情况下 长度为 Long.MAX_VALUE
            this.est = Long.MAX_VALUE;
            this.characteristics = characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED);
        }

        /**
         * 将该元素进行拆分
         * @return
         */
        @Override
        public Spliterator<T> trySplit() {
            /*
             * Split into arrays of arithmetically increasing batch
             * sizes.  This will only improve parallel performance if
             * per-element Consumer actions are more costly than
             * transferring them into an array.  The use of an
             * arithmetic progression in split sizes provides overhead
             * vs parallelism bounds that do not particularly favor or
             * penalize cases of lightweight vs heavyweight element
             * operations, across combinations of #elements vs #cores,
             * whether or not either are known.  We generate
             * O(sqrt(#elements)) splits, allowing O(sqrt(#cores))
             * potential speedup.
             */
            Iterator<? extends T> i;
            long s;
            // 2种初始化方式 一种通过容器 一种通过迭代器 如果没有设置迭代器 就从容器中 获取 同时使用的 size属性也是正常的
            if ((i = it) == null) {
                i = it = collection.iterator();
                s = est = (long) collection.size();
            }
            else
                // 代表使用迭代器进行初始化的情况 这时 长度信息是自由设置的
                s = est;
            // 首先要求迭代器 必须还有元素
            if (s > 1 && i.hasNext()) {
                // batch 默认是0
                /**
                 * 第一次n = 0 + BATCH_UNIT
                 * 第二次 因为如果batch小于BATCH_UNIT 代表迭代器剩余元素小于 BATCH_UNIT 是过不了 i.hasNext()的 所以 n = 2*BATCH_UNIT
                 * 假设迭代器元素非常大
                 * 第三次 3*BATCH_UNIT    也就是每次返回的ArrayS 对象都是递增 BATCH_UNIT 的大小(元素是不重叠的)
                 *
                 * 这里存在一个变数 就是 上面情况s 假设是MAX_VALUE 不会限制每次分配的大小
                 * 如果是迭代器本身的大小
                 * 那么在条件允许的情况下 还是不断递增获取 n*BATCH_UNIT 的数量 一旦出现超过迭代器长度的大小 本次就只会分配剩余的大小 这时batch本身已经不重要了
                 * 因为下次尝试拆分时  i.hasNext() == false 不满足条件
                 *
                 * 如果est 直接被设置了一个 小于迭代器大小的值 这样 est -= j 就会是一个负数 不满足 s>1的条件 但是那一次拆分的数据还是正常的
                 * 也就是est 如果设小了 只会减少拆分次数 而具体拆分返回的元素数据还是由迭代器本身的元素数量决定的
                 */
                int n = batch + BATCH_UNIT;
                // 如果超过了迭代器本身长度 设置成迭代器等长 但是该值是可控的 可以随意设置
                if (n > s)
                    n = (int) s;
                // 不能超过允许的 最大批数量
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                // 创建一个 min(BATCH_UNIT，Collection.size()) 长度的数组
                Object[] a = new Object[n];
                int j = 0;
                // 将迭代器中所有数据拷贝到该数组上  2种情况 1: 迭代器大小超过Batch_unit 设置 batch_unit 的元素 2: 设置迭代器大小的元素
                // 如果迭代器有超过1024个元素 比如2000 第一次 调用1024次next() 分配 这么多 元素 之后剩余的900多元素就会在下次通过 next() 获取
                do { a[j] = i.next(); } while (++j < n && i.hasNext());
                // 更新本次已分配的长度
                batch = j;
                // 将est 减去迭代器长度  因为总长度同时减小了 保证 est-batch 在一个合理的范围 不会超过 迭代器可提供的元素数量
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new ArraySpliterator<>(a, 0, j, characteristics);
            }
            return null;
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            if (action == null) throw new NullPointerException();
            Iterator<? extends T> i;
            if ((i = it) == null) {
                i = it = collection.iterator();
                est = (long)collection.size();
            }
            i.forEachRemaining(action);
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            if (action == null) throw new NullPointerException();
            if (it == null) {
                it = collection.iterator();
                est = (long) collection.size();
            }
            if (it.hasNext()) {
                action.accept(it.next());
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() {
            if (it == null) {
                it = collection.iterator();
                return est = (long)collection.size();
            }
            return est;
        }

        @Override
        public int characteristics() { return characteristics; }

        /**
         * 该方法应该是 返回 该迭代器是使用什么 Comparator 进行排序的 如果一开始就是顺序的 就返回null
         * @return
         */
        @Override
        public Comparator<? super T> getComparator() {
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }

    /**
     * A Spliterator.OfInt using a given IntStream.IntIterator for element
     * operations. The spliterator implements {@code trySplit} to
     * permit limited parallelism.
     * 基于int类型的 可拆分迭代器
     */
    static final class IntIteratorSpliterator implements Spliterator.OfInt {
        static final int BATCH_UNIT = IteratorSpliterator.BATCH_UNIT;
        static final int MAX_BATCH = IteratorSpliterator.MAX_BATCH;
        private PrimitiveIterator.OfInt it;
        private final int characteristics;
        private long est;             // size estimate
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator using the given iterator
         * for traversal, and reporting the given initial size
         * and characteristics.
         *
         * @param iterator the iterator for the source
         * @param size the number of elements in the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         */
        public IntIteratorSpliterator(PrimitiveIterator.OfInt iterator, long size, int characteristics) {
            this.it = iterator;
            this.est = size;
            this.characteristics = (characteristics & Spliterator.CONCURRENT) == 0
                                   ? characteristics | Spliterator.SIZED | Spliterator.SUBSIZED
                                   : characteristics;
        }

        /**
         * Creates a spliterator using the given iterator for a
         * source of unknown size, reporting the given
         * characteristics.
         *
         * @param iterator the iterator for the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         */
        public IntIteratorSpliterator(PrimitiveIterator.OfInt iterator, int characteristics) {
            this.it = iterator;
            this.est = Long.MAX_VALUE;
            this.characteristics = characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED);
        }

        @Override
        public OfInt trySplit() {
            PrimitiveIterator.OfInt i = it;
            long s = est;
            if (s > 1 && i.hasNext()) {
                int n = batch + BATCH_UNIT;
                if (n > s)
                    n = (int) s;
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                int[] a = new int[n];
                int j = 0;
                do { a[j] = i.nextInt(); } while (++j < n && i.hasNext());
                batch = j;
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new IntArraySpliterator(a, 0, j, characteristics);
            }
            return null;
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            if (action == null) throw new NullPointerException();
            it.forEachRemaining(action);
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            if (action == null) throw new NullPointerException();
            if (it.hasNext()) {
                action.accept(it.nextInt());
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() {
            return est;
        }

        @Override
        public int characteristics() { return characteristics; }

        @Override
        public Comparator<? super Integer> getComparator() {
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }

    static final class LongIteratorSpliterator implements Spliterator.OfLong {
        static final int BATCH_UNIT = IteratorSpliterator.BATCH_UNIT;
        static final int MAX_BATCH = IteratorSpliterator.MAX_BATCH;
        private PrimitiveIterator.OfLong it;
        private final int characteristics;
        private long est;             // size estimate
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator using the given iterator
         * for traversal, and reporting the given initial size
         * and characteristics.
         *
         * @param iterator the iterator for the source
         * @param size the number of elements in the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         */
        public LongIteratorSpliterator(PrimitiveIterator.OfLong iterator, long size, int characteristics) {
            this.it = iterator;
            this.est = size;
            this.characteristics = (characteristics & Spliterator.CONCURRENT) == 0
                                   ? characteristics | Spliterator.SIZED | Spliterator.SUBSIZED
                                   : characteristics;
        }

        /**
         * Creates a spliterator using the given iterator for a
         * source of unknown size, reporting the given
         * characteristics.
         *
         * @param iterator the iterator for the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         */
        public LongIteratorSpliterator(PrimitiveIterator.OfLong iterator, int characteristics) {
            this.it = iterator;
            this.est = Long.MAX_VALUE;
            this.characteristics = characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED);
        }

        @Override
        public OfLong trySplit() {
            PrimitiveIterator.OfLong i = it;
            long s = est;
            if (s > 1 && i.hasNext()) {
                int n = batch + BATCH_UNIT;
                if (n > s)
                    n = (int) s;
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                long[] a = new long[n];
                int j = 0;
                do { a[j] = i.nextLong(); } while (++j < n && i.hasNext());
                batch = j;
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new LongArraySpliterator(a, 0, j, characteristics);
            }
            return null;
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            if (action == null) throw new NullPointerException();
            it.forEachRemaining(action);
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            if (action == null) throw new NullPointerException();
            if (it.hasNext()) {
                action.accept(it.nextLong());
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() {
            return est;
        }

        @Override
        public int characteristics() { return characteristics; }

        @Override
        public Comparator<? super Long> getComparator() {
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }

    static final class DoubleIteratorSpliterator implements Spliterator.OfDouble {
        static final int BATCH_UNIT = IteratorSpliterator.BATCH_UNIT;
        static final int MAX_BATCH = IteratorSpliterator.MAX_BATCH;
        private PrimitiveIterator.OfDouble it;
        private final int characteristics;
        private long est;             // size estimate
        private int batch;            // batch size for splits

        /**
         * Creates a spliterator using the given iterator
         * for traversal, and reporting the given initial size
         * and characteristics.
         *
         * @param iterator the iterator for the source
         * @param size the number of elements in the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         */
        public DoubleIteratorSpliterator(PrimitiveIterator.OfDouble iterator, long size, int characteristics) {
            this.it = iterator;
            this.est = size;
            this.characteristics = (characteristics & Spliterator.CONCURRENT) == 0
                                   ? characteristics | Spliterator.SIZED | Spliterator.SUBSIZED
                                   : characteristics;
        }

        /**
         * Creates a spliterator using the given iterator for a
         * source of unknown size, reporting the given
         * characteristics.
         *
         * @param iterator the iterator for the source
         * @param characteristics properties of this spliterator's
         * source or elements.
         */
        public DoubleIteratorSpliterator(PrimitiveIterator.OfDouble iterator, int characteristics) {
            this.it = iterator;
            this.est = Long.MAX_VALUE;
            this.characteristics = characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED);
        }

        @Override
        public OfDouble trySplit() {
            PrimitiveIterator.OfDouble i = it;
            long s = est;
            if (s > 1 && i.hasNext()) {
                int n = batch + BATCH_UNIT;
                if (n > s)
                    n = (int) s;
                if (n > MAX_BATCH)
                    n = MAX_BATCH;
                double[] a = new double[n];
                int j = 0;
                do { a[j] = i.nextDouble(); } while (++j < n && i.hasNext());
                batch = j;
                if (est != Long.MAX_VALUE)
                    est -= j;
                return new DoubleArraySpliterator(a, 0, j, characteristics);
            }
            return null;
        }

        @Override
        public void forEachRemaining(DoubleConsumer action) {
            if (action == null) throw new NullPointerException();
            it.forEachRemaining(action);
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            if (action == null) throw new NullPointerException();
            if (it.hasNext()) {
                action.accept(it.nextDouble());
                return true;
            }
            return false;
        }

        @Override
        public long estimateSize() {
            return est;
        }

        @Override
        public int characteristics() { return characteristics; }

        @Override
        public Comparator<? super Double> getComparator() {
            if (hasCharacteristics(Spliterator.SORTED))
                return null;
            throw new IllegalStateException();
        }
    }
}
