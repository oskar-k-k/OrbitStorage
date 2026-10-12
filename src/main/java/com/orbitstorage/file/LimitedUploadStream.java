package com.orbitstorage.file;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;

/** Enforces declared size and a maximum upload duration, without buffering the body. */
public class LimitedUploadStream extends FilterInputStream {
    private final long expected;
    private final long deadline=System.nanoTime()+java.time.Duration.ofMinutes(15).toNanos();
    private long count;
    public LimitedUploadStream(InputStream source,long expected) { super(source); this.expected=expected; }
    private void check(long bytes) throws IOException {
        if (System.nanoTime()>deadline) throw new IOException("Upload deadline exceeded");
        count+=bytes;
        if (count>expected) throw new IOException("Upload exceeds declared length");
    }
    @Override public int read() throws IOException {
        check(0); int value=in.read(); if(value>=0) check(1); else verifyComplete(); return value;
    }
    @Override public int read(byte[] b,int offset,int length) throws IOException {
        check(0); int read=in.read(b,offset,(int)Math.min(length,Math.max(1,expected-count+1)));
        if(read>=0) check(read); else verifyComplete(); return read;
    }
    @Override public long skip(long n) throws IOException { long skipped=in.skip(Math.min(n,expected-count)); check(skipped); return skipped; }
    @Override public boolean markSupported() { return false; }
    @Override public void reset() throws IOException { throw new IOException("Stream cannot be reset"); }
    public void verifyComplete() throws IOException { if(count!=expected) throw new IOException("Incomplete upload body"); }
}
