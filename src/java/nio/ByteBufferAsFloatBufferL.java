/*
 * Copyright (c) 2000, 2013, Oracle and/or its affiliates. All rights reserved.
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

// -- This file was mechanically generated: Do not edit! -- //

package java.nio;


/**
 * 基于小端法   所以 ByteBuffer本身是没有大端小端的区别的 他只是byte流  涉及到 byte 与其他基本类型的转换时 才真正使用到 大端小端 按照类型长度读取 byte流 并使用位运算计算真正的值
 */
class ByteBufferAsFloatBufferL                  // package-private
    extends FloatBuffer
{



    protected final ByteBuffer bb;
    protected final int offset;



    ByteBufferAsFloatBufferL(ByteBuffer bb) {   // package-private

        super(-1, 0,
              bb.remaining() >> 2,
              bb.remaining() >> 2);
        this.bb = bb;
        // enforce limit == capacity
        int cap = this.capacity();
        this.limit(cap);
        int pos = this.position();
        assert (pos <= cap);
        offset = pos;



    }

    ByteBufferAsFloatBufferL(ByteBuffer bb,
                                     int mark, int pos, int lim, int cap,
                                     int off)
    {

        super(mark, pos, lim, cap);
        this.bb = bb;
        offset = off;



    }

    public FloatBuffer slice() {
        int pos = this.position();
        int lim = this.limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);
        int off = (pos << 2) + offset;
        assert (off >= 0);
        return new ByteBufferAsFloatBufferL(bb, -1, 0, rem, rem, off);
    }

    public FloatBuffer duplicate() {
        return new ByteBufferAsFloatBufferL(bb,
                                                    this.markValue(),
                                                    this.position(),
                                                    this.limit(),
                                                    this.capacity(),
                                                    offset);
    }

    public FloatBuffer asReadOnlyBuffer() {

        return new ByteBufferAsFloatBufferRL(bb,
                                                 this.markValue(),
                                                 this.position(),
                                                 this.limit(),
                                                 this.capacity(),
                                                 offset);



    }



    protected int ix(int i) {
        return (i << 2) + offset;
    }

    public float get() {
        return Bits.getFloatL(bb, ix(nextGetIndex()));
    }

    public float get(int i) {
        return Bits.getFloatL(bb, ix(checkIndex(i)));
    }









    public FloatBuffer put(float x) {

        Bits.putFloatL(bb, ix(nextPutIndex()), x);
        return this;



    }

    public FloatBuffer put(int i, float x) {

        Bits.putFloatL(bb, ix(checkIndex(i)), x);
        return this;



    }

    public FloatBuffer compact() {

        int pos = position();
        int lim = limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);

        ByteBuffer db = bb.duplicate();
        db.limit(ix(lim));
        db.position(ix(0));
        ByteBuffer sb = db.slice();
        sb.position(pos << 2);
        sb.compact();
        position(rem);
        limit(capacity());
        discardMark();
        return this;



    }

    public boolean isDirect() {
        return bb.isDirect();
    }

    public boolean isReadOnly() {
        return false;
    }











































    public ByteOrder order() {




        return ByteOrder.LITTLE_ENDIAN;

    }

}
