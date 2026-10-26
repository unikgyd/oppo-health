package dev.local.stepcontroller;

/** Pins one target to one actual synchronous sync-worker invocation. */
public final class WechatScope {
    private final ThreadLocal<Frame> current = new ThreadLocal<>();

    public Frame enter(int steps) {
        if (steps < 1 || steps > StepPlan.MAX_STEPS) throw new IllegalArgumentException("Invalid WeChat steps");
        Frame frame = new Frame(steps, current.get());
        current.set(frame);
        return frame;
    }

    public Integer steps() {
        Frame frame = current.get();
        return frame == null ? null : frame.steps;
    }

    public void clear() { current.remove(); }

    public final class Frame implements AutoCloseable {
        private final int steps;
        private final Frame previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private Frame(int steps, Frame previous) { this.steps = steps; this.previous = previous; }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Scope closed from another thread");
            if (closed) return;
            if (current.get() != this) throw new IllegalStateException("Scope closed out of order");
            if (previous == null) current.remove();
            else current.set(previous);
            closed = true;
        }
    }
}
