package com.geely.owwprobe;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.Debug;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Locale;
import org.tensorflow.lite.Interpreter;

/** Measures the fixed-shape openWakeWord TFLite streaming pipeline. */
public final class OpenWakeWordProbeActivity extends Activity {
    private static final int WARMUP_FRAMES = 50;
    private static final int MEASURED_FRAMES = 2000;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(24f);
        status.setPadding(40, 40, 40, 40);
        status.setText("Running fixed-shape openWakeWord TFLite benchmark…");
        setContentView(status);
        new Thread(this::runBenchmark, "oww-benchmark").start();
    }

    private void runBenchmark() {
        String result;
        Interpreter.Options options = new Interpreter.Options().setNumThreads(1);
        try (Interpreter mel = new Interpreter(mapAsset("melspectrogram_1760.tflite"), options);
             Interpreter embedding = new Interpreter(mapAsset("embedding_model.tflite"), options);
             Interpreter wakeword = new Interpreter(mapAsset("hey_jarvis_v0.1.tflite"), options)) {
            int[] melInShape = mel.getInputTensor(0).shape();
            int[] melOutShape = mel.getOutputTensor(0).shape();
            if (!Arrays.equals(melInShape, new int[] {1, 1760})
                    || !Arrays.equals(melOutShape, new int[] {1, 1, 8, 32})) {
                throw new IllegalStateException("unexpected mel tensors: "
                    + Arrays.toString(melInShape) + " -> " + Arrays.toString(melOutShape));
            }
            Pipeline pipeline = new Pipeline(mel, embedding, wakeword);
            for (int i = 0; i < WARMUP_FRAMES; i++) pipeline.run(false);
            Runtime runtime = Runtime.getRuntime();
            System.gc();
            long pssBeforeKb = Debug.getPss();
            long javaBefore = runtime.totalMemory() - runtime.freeMemory();
            long wallStart = System.nanoTime();
            long cpuStart = Debug.threadCpuTimeNanos();
            for (int i = 0; i < MEASURED_FRAMES; i++) pipeline.run(true);
            long cpuNs = Debug.threadCpuTimeNanos() - cpuStart;
            long wallNs = System.nanoTime() - wallStart;
            long pssAfterKb = Debug.getPss();
            long javaAfter = runtime.totalMemory() - runtime.freeMemory();
            double audioSeconds = MEASURED_FRAMES * 0.080;
            result = String.format(Locale.US,
                "openWakeWord v0.5.1 fixed TFLite / LiteRT 1.4.0 (1 thread)\n"
                + "ABI=%s frames=%d audio=%.1fs\nmel=%s -> %s\n"
                + "wall=%.3fs cpu=%.3fs realtime-factor=%.4f\nCPU=%.2f%% of one core\n"
                + "per-frame: mel=%.3fms embed=%.3fms wake=%.3fms pipeline=%.3fms\n"
                + "PSS before/after=%d/%d KiB Java delta=%d KiB score=%.6f",
                Arrays.toString(android.os.Build.SUPPORTED_ABIS), MEASURED_FRAMES, audioSeconds,
                Arrays.toString(melInShape), Arrays.toString(melOutShape), wallNs / 1e9,
                cpuNs / 1e9, wallNs / 1e9 / audioSeconds,
                cpuNs / 1e9 / audioSeconds * 100.0,
                pipeline.melNs / 1e6 / MEASURED_FRAMES,
                pipeline.embeddingNs / 1e6 / MEASURED_FRAMES,
                pipeline.wakeNs / 1e6 / MEASURED_FRAMES,
                wallNs / 1e6 / MEASURED_FRAMES, pssBeforeKb, pssAfterKb,
                (javaAfter - javaBefore) / 1024, pipeline.score);
        } catch (Throwable t) {
            result = "FAILED: " + t.getClass().getName() + ": " + t.getMessage();
            android.util.Log.e("OwwProbe", result, t);
        }
        writeResult(result);
        String finalResult = result;
        runOnUiThread(() -> status.setText(finalResult));
        android.util.Log.i("OwwProbe", result.replace('\n', ' '));
    }

    private static final class Pipeline {
        final Interpreter mel, embedding, wakeword;
        final ByteBuffer audio = floats(1760), melOutput = floats(8 * 32);
        final ByteBuffer embeddingInput = floats(76 * 32), embeddingOutput = floats(96);
        final ByteBuffer wakeInput = floats(16 * 96), wakeOutput = floats(1);
        final float[] melHistory = new float[76 * 32];
        final float[] embeddingHistory = new float[16 * 96];
        long melNs, embeddingNs, wakeNs;
        float score;

        Pipeline(Interpreter mel, Interpreter embedding, Interpreter wakeword) {
            this.mel = mel; this.embedding = embedding; this.wakeword = wakeword;
        }

        void run(boolean measure) {
            long start = System.nanoTime();
            audio.rewind(); melOutput.rewind(); mel.run(audio, melOutput);
            melOutput.rewind();
            System.arraycopy(melHistory, 8 * 32, melHistory, 0, 68 * 32);
            for (int i = 68 * 32; i < 76 * 32; i++) melHistory[i] = melOutput.getFloat() / 10f + 2f;
            long afterMel = System.nanoTime();
            embeddingInput.rewind(); embeddingInput.asFloatBuffer().put(melHistory);
            embeddingOutput.rewind(); embedding.run(embeddingInput, embeddingOutput);
            embeddingOutput.rewind();
            System.arraycopy(embeddingHistory, 96, embeddingHistory, 0, 15 * 96);
            for (int i = 15 * 96; i < 16 * 96; i++) embeddingHistory[i] = embeddingOutput.getFloat();
            long afterEmbedding = System.nanoTime();
            wakeInput.rewind(); wakeInput.asFloatBuffer().put(embeddingHistory);
            wakeOutput.rewind(); wakeword.run(wakeInput, wakeOutput);
            wakeOutput.rewind(); score = wakeOutput.getFloat();
            long afterWake = System.nanoTime();
            if (measure) {
                melNs += afterMel - start;
                embeddingNs += afterEmbedding - afterMel;
                wakeNs += afterWake - afterEmbedding;
            }
        }
    }

    private static ByteBuffer floats(int count) {
        return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder());
    }

    private MappedByteBuffer mapAsset(String name) throws Exception {
        AssetFileDescriptor descriptor = getAssets().openFd(name);
        try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             FileChannel channel = input.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, descriptor.getStartOffset(),
                descriptor.getDeclaredLength());
        } finally {
            descriptor.close();
        }
    }

    private void writeResult(String value) {
        try {
            File output = new File(getExternalFilesDir(null), "openwakeword-result.txt");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write(value.getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            android.util.Log.e("OwwProbe", "result write failed", t);
        }
    }
}
