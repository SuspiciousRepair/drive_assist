package com.geely.voiceprobe;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class WavWriter implements Closeable {
    private static final int SAMPLE_RATE = 16000;
    private final RandomAccessFile file;
    private int dataBytes;

    WavWriter(File output) throws IOException {
        file = new RandomAccessFile(output, "rw");
        file.setLength(0);
        file.write(new byte[44]);
    }

    void write(short[] samples, int count) throws IOException {
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xff);
            bytes[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xff);
        }
        file.write(bytes);
        dataBytes += bytes.length;
    }

    @Override public void close() throws IOException {
        file.seek(0);
        writeAscii("RIFF");
        writeIntLE(36 + dataBytes);
        writeAscii("WAVEfmt ");
        writeIntLE(16);
        writeShortLE(1);
        writeShortLE(1);
        writeIntLE(SAMPLE_RATE);
        writeIntLE(SAMPLE_RATE * 2);
        writeShortLE(2);
        writeShortLE(16);
        writeAscii("data");
        writeIntLE(dataBytes);
        file.close();
    }

    private void writeAscii(String value) throws IOException {
        file.write(value.getBytes("US-ASCII"));
    }

    private void writeShortLE(int value) throws IOException {
        file.write(value & 0xff);
        file.write((value >>> 8) & 0xff);
    }

    private void writeIntLE(int value) throws IOException {
        writeShortLE(value & 0xffff);
        writeShortLE((value >>> 16) & 0xffff);
    }
}
