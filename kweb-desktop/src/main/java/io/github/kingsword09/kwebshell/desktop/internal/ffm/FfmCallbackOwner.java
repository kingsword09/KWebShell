package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.Arena;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

abstract class FfmCallbackOwner {
    private static final long RELEASE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Object lifecycleLock = new Object();
    private final Arena arena = Arena.ofShared();
    private final FfmCallbacks.Failure failureSink;
    private final AtomicLong handle = new AtomicLong();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private int activeCallbacks;
    private boolean terminalReturned;
    private boolean closed;

    FfmCallbackOwner(FfmCallbacks.Failure failureSink) {
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    final Arena arena() {
        return arena;
    }

    final long handle() {
        return handle.get();
    }

    final boolean bindHandle(long value) {
        if (value <= 0) {
            recordFailure(
                "native.ffm.callback-handle-invalid",
                "A native callback published an invalid owner handle.",
                null
            );
            return false;
        }
        long observed = handle.compareAndExchange(0, value);
        if (observed == 0 || observed == value) {
            return true;
        }
        recordFailure(
            "native.ffm.callback-handle-mismatch",
            "A native callback changed its FFM owner handle.",
            null
        );
        return false;
    }

    final boolean beginCallback() {
        synchronized (lifecycleLock) {
            if (closed || terminalReturned) {
                return false;
            }
            activeCallbacks++;
            return true;
        }
    }

    final void finishCallback(boolean terminal) {
        synchronized (lifecycleLock) {
            if (activeCallbacks <= 0) {
                recordFailure(
                    "native.ffm.callback-accounting-invalid",
                    "The FFM callback owner observed an unmatched callback return.",
                    null
                );
                return;
            }
            activeCallbacks--;
            if (terminal) {
                if (terminalReturned) {
                    recordFailure(
                        "native.ffm.callback-terminal-duplicate",
                        "The FFM callback owner received more than one terminal callback.",
                        null
                    );
                }
                terminalReturned = true;
            }
            lifecycleLock.notifyAll();
        }
    }

    final Throwable releaseAfterTerminal() {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("The FFM callback owner is already closed.");
            }
            long remaining = RELEASE_TIMEOUT_NANOS;
            long started = System.nanoTime();
            while (!terminalReturned || activeCallbacks != 0) {
                if (remaining <= 0) {
                    throw new IllegalStateException(
                        "The FFM callback owner did not reach terminal quiescence before release."
                    );
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "Waiting for FFM callback owner release was interrupted.",
                        error
                    );
                }
                remaining = RELEASE_TIMEOUT_NANOS - (System.nanoTime() - started);
            }
            closed = true;
        }
        arena.close();
        return failure.get();
    }

    final void abortBeforeOwnership() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            if (activeCallbacks != 0) {
                throw new IllegalStateException("An FFM callback is active while creation is aborting.");
            }
            closed = true;
        }
        arena.close();
    }

    final boolean isTerminalReturned() {
        synchronized (lifecycleLock) {
            return terminalReturned;
        }
    }

    final boolean isClosed() {
        synchronized (lifecycleLock) {
            return closed;
        }
    }

    final Throwable failure() {
        return failure.get();
    }

    final void recordFailure(String code, String message, Throwable cause) {
        Throwable recorded = cause == null ? new IllegalStateException(message) : cause;
        failure.compareAndSet(null, recorded);
        try {
            failureSink.onFailure(code, message, recorded);
        } catch (Throwable sinkFailure) {
            if (sinkFailure != recorded) {
                recorded.addSuppressed(sinkFailure);
            }
            failure.compareAndSet(null, recorded);
        }
    }
}
