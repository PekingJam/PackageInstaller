package io.github.vvb2060.packageinstaller.datamodel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class OutputStreamDataSink implements DataSink {
    private final OutputStream out;
    private final Consumer<Integer> callback;
    private byte[] tmpBuffer;

    public OutputStreamDataSink(OutputStream out, Consumer<Integer> callback) {
        this.out = out;
        this.callback = callback;
    }

    @Override
    public void consume(ByteBuffer buf) throws IOException {
        if (!buf.hasRemaining()) {
            return;
        }
        var remaining = buf.remaining();

        if (buf.hasArray()) {
            out.write(buf.array(), buf.arrayOffset() + buf.position(), remaining);
            buf.position(buf.limit());
        } else {
            // 源 buffer 可能是 direct 的（FileDataSource.feed 就是），不能对 direct buffer 调 array()，
            // 这里换成堆上的 byte[] 做中转。
            if (tmpBuffer == null) {
                tmpBuffer = new byte[64 * 1024];
            }
            while (buf.hasRemaining()) {
                int chunkSize = Math.min(buf.remaining(), tmpBuffer.length);
                buf.get(tmpBuffer, 0, chunkSize);
                out.write(tmpBuffer, 0, chunkSize);
            }
        }
        callback.accept(remaining);
    }
}
