package com.k1af.ft8af.rigs;

import android.util.Log;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.wave.HamlibNative;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * A {@link BaseRig} backed by hamlib instead of a hand-rolled per-manufacturer
 * byte table. hamlib supplies the CAT protocol for the given model; this class
 * only marshals between the app and hamlib.
 *
 * <p>Transport is unchanged: hamlib's CAT bytes are handed to the existing
 * connector via {@code sink}, and bytes the radio sends back arrive at
 * {@link #onReceiveData(byte[])} and are fed into hamlib. See
 * {@link HamlibNative} and {@code cpp/ft8af_glue/hamlib_jni.cpp}.
 *
 * <p>hamlib's {@code rig_*} calls are synchronous CAT round-trips, so they run
 * on a private single-thread executor — never the UI thread and never the
 * serial read thread (which delivers {@link #onReceiveData}); otherwise a
 * blocking call could never receive its own ack.
 */
public class HamlibRig extends BaseRig {
    private static final String TAG = "HamlibRig";

    /** hamlib mode for FT8/FT4: DATA-USB, so audio routes through the data path. */
    private static final String FT_MODE = "PKTUSB";

    /**
     * hamlib's RIG_MODEL_QRPLABS_QMX (RIG_MAKE_MODEL(RIG_KENWOOD, 57) = 2057).
     * hamlib's generic {@code kenwood_set_mode} has no knowledge of the QMX's
     * own vendor-specific {@code Q41;} command, which the radio needs after
     * {@code MD6;} (and again after any {@code FA...;} frequency change, which
     * otherwise silently reverts the radio from Digital back to USB) to
     * actually commit to and stay in Digital/Data mode — the same gap Dan
     * Lee's native app had to hand-patch for the same radio. hamlib's own
     * mode-setting is otherwise correct for every other Hamlib-backed rig in
     * this list, so this follow-up is scoped to this one model only.
     */
    private static final int QRPLABS_QMX_MODEL_ID = 2057;

    private final int modelId;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile long handle = 0;
    /**
     * Set once in {@link #onDisconnecting()}. After that the {@link #worker}
     * executor has been shut down, so any further {@code worker.submit(...)}
     * would throw {@link RejectedExecutionException}. Guarding on this flag (and
     * catching the race with the shutdown) keeps a post-disconnect CAT call — or
     * a second {@code onDisconnecting()} — from crashing the app.
     */
    private volatile boolean closed = false;

    /** Receives CAT bytes hamlib wants sent to the radio; forwards to connector. */
    private final HamlibNative.HamlibSink sink = data -> {
        BaseRigConnector c = getConnector();
        if (c != null) {
            c.sendData(data);
        } else {
            // DIAGNOSTIC (temporary): hamlib's CAT bytes are silently dropped
            // here with no trace anywhere else in the app or native logs when
            // the connector is null — suspected cause of commands beyond the
            // initial rig_open() handshake (e.g. FA;) never reaching the radio.
            Log.w(TAG, "sink.onBytesToRig: dropping " + data.length
                    + " byte(s), getConnector() is null");
        }
    };

    public HamlibRig(int modelId) {
        this.modelId = modelId;
    }

    /**
     * Send a raw CAT command straight to the connector, bypassing hamlib
     * entirely. Only for vendor-specific strings hamlib has no concept of
     * (like QMX's {@code Q41;}) — anything hamlib understands should go
     * through the normal {@code nativeSet*} calls so hamlib's own internal
     * rig-state tracking stays correct.
     */
    private void sendRawCat(String cmd) {
        BaseRigConnector c = getConnector();
        if (c != null) {
            c.sendData(cmd.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } else {
            Log.w(TAG, "sendRawCat(" + cmd + "): dropping, getConnector() is null");
        }
    }

    /**
     * Open hamlib once the transport is connected. hamlib's {@code rig_open}
     * performs an initial CAT handshake, so the connector must be live and
     * forwarding bytes first. Called at the head of each worker task; safe to
     * call repeatedly.
     */
    private synchronized boolean ensureOpen() {
        if (closed) {
            // Torn down (or being torn down): never re-open. A task that was
            // queued just before onDisconnecting() could otherwise open a fresh
            // handle here that the close task (which captured the old handle)
            // never releases — leaking the rig and leaving it open post-disconnect.
            return false;
        }
        if (handle != 0) {
            return true;
        }
        BaseRigConnector c = getConnector();
        if (c == null || !c.isConnected() || !HamlibNative.isAvailable()) {
            return false;
        }
        long h = HamlibNative.nativeOpen(modelId, sink);
        if (h == 0) {
            Log.w(TAG, "nativeOpen failed for hamlib model " + modelId);
            return false;
        }
        handle = h;
        Log.i(TAG, "hamlib model " + modelId + " opened");
        return true;
    }

    @Override
    public boolean isConnected() {
        BaseRigConnector c = getConnector();
        return c != null && c.isConnected();
    }

    @Override
    public void onReceiveData(byte[] data) {
        // Called on the serial read thread — a quick, non-blocking socket write.
        long h = handle;
        if (h != 0) {
            HamlibNative.nativeFeedFromRig(h, data);
        }
    }

    /**
     * Submit a CAT task to the worker, tolerating a rig that is (or is being)
     * torn down. Once {@link #onDisconnecting()} has shut the executor down,
     * {@code worker.submit(...)} would throw {@link RejectedExecutionException}
     * on the caller's thread (the UI thread, or the TX sequencer for PTT) and
     * crash the app; here the request is simply dropped instead.
     */
    private void submitToWorker(Runnable task) {
        if (closed) return; // fast path: don't even enqueue after disconnect
        try {
            worker.submit(() -> {
                // Re-check at execution time: a task enqueued a moment before
                // onDisconnecting() set closed can still be sitting in the queue
                // when the rig is torn down. Running its CAT body then would call
                // ensureOpen() and could re-open the handle after disconnect.
                if (closed) return;
                task.run();
            });
        } catch (RejectedExecutionException e) {
            // Raced onDisconnecting()'s shutdown; the rig is gone — drop the task.
            Log.w(TAG, "worker rejected task after disconnect");
        }
    }

    /** Visible for tests: submit a raw task through the same closed-gate. */
    void submitForTest(Runnable task) {
        submitToWorker(task);
    }

    /** Visible for tests: block until the worker has fully drained/terminated. */
    void awaitWorkerForTest() throws InterruptedException {
        worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void setUsbModeToRig() {
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            int rc = HamlibNative.nativeSetMode(handle, FT_MODE);
            if (rc != 0) Log.w(TAG, "set_mode(" + FT_MODE + ") rc=" + rc);
            if (modelId == QRPLABS_QMX_MODEL_ID) {
                sendRawCat("Q41;");
            }
        });
    }

    @Override
    public void setFreqToRig() {
        // getFreq() holds the app's target (set via setFreq() just before this).
        // The app already picks an in-band FT8 frequency, so this never QSYs to
        // an untuned band — hamlib just sets exactly what the app asked for.
        final long hz = getFreq();
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            int rc = HamlibNative.nativeSetFreq(handle, hz);
            if (rc != 0) Log.w(TAG, "set_freq(" + hz + ") rc=" + rc);
            if (modelId == QRPLABS_QMX_MODEL_ID) {
                // FA...; alone silently reverts the QMX from Digital back to
                // USB (observed on real hardware) — re-assert Q41; every time
                // frequency changes, not just once at mode-set time.
                sendRawCat("Q41;");
            }
        });
    }

    @Override
    public void readFreqFromRig() {
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            long f = HamlibNative.nativeGetFreq(handle);
            if (f > 0) {
                setFreq(f); // updates dial + fires CAT-liveness (BaseRig)
            }
            if (modelId == QRPLABS_QMX_MODEL_ID) {
                // rig_get_freq() sends the same FA; command as a query (no
                // parameter) — observed on real hardware to revert the QMX
                // from Digital to USB the same as a frequency set does, and
                // this is very likely called on a periodic poll, so it needs
                // the same Q41; follow-up or mode keeps reverting repeatedly.
                sendRawCat("Q41;");
            }
        });
    }

    @Override
    public void setPTT(boolean on) {
        super.setPTT(on); // notify listeners / meter state
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            int rc = HamlibNative.nativeSetPtt(handle, on);
            if (rc != 0) Log.w(TAG, "set_ptt(" + on + ") rc=" + rc);
        });
    }

    @Override
    public String getName() {
        return "Hamlib";
    }

    @Override
    public void onDisconnecting() {
        // Idempotent: this can be called more than once on the same instance
        // (e.g. a user disconnect via CableConnector.disconnect() followed by a
        // reconnect through connectRig(), which does not null the old rig).
        // Shutting the worker down twice — or submitting to it after the first
        // shutdown — would throw RejectedExecutionException and crash the app.
        if (closed) return;
        closed = true;
        final long h = handle;
        handle = 0;
        try {
            worker.submit(() -> {
                if (h != 0) {
                    HamlibNative.nativeClose(h);
                }
            });
        } catch (RejectedExecutionException e) {
            // Worker already terminated, so it can't run the close for us. The
            // handle is still reachable via the local h — close it inline rather
            // than leaking the native rig/pump resources.
            Log.w(TAG, "worker rejected close task; closing handle inline");
            if (h != 0) {
                HamlibNative.nativeClose(h);
            }
        }
        worker.shutdown();
    }
}
