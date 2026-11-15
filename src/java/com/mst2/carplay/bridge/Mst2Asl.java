/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
 */
package com.mst2.carplay.bridge;

import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.genericevents.ThreadSwitchingTarget;

// DSI proxy and reflection factory belong to the ASL task; other threads race with the stock targets at boot.
public final class Mst2Asl {
    public static final int DONE = 0;
    public static final int PENDING = 1;
    public static final int NO_ASL = 2;

    private Mst2Asl() {
    }

    public abstract static class Job implements Runnable {
        private boolean done;
        private Throwable error;

        protected abstract void work() throws Exception;

        public final void run() {
            try {
                work();
            } catch (Throwable t) {
                error = t;
            } finally {
                synchronized (this) {
                    done = true;
                    notifyAll();
                }
            }
        }

        public final synchronized boolean isDone() {
            return done;
        }

        public final synchronized boolean await(long ms) {
            long left = ms;
            while (!done && left > 0L) {
                long start = System.currentTimeMillis();
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return done;
                }
                long spent = System.currentTimeMillis() - start;
                left -= spent > 0L ? spent : 1L;
            }
            return done;
        }

        public final Throwable error() {
            return error;
        }
    }

    public static int run(Job job, long waitMs) {
        ThreadSwitchingTarget asl = ServiceManager.aslThreadSwitch;
        if (asl == null) return NO_ASL;
        boolean change;
        try {
            change = asl.isThreadChangeNecessary();
        } catch (Throwable t) {
            return NO_ASL;
        }
        if (!change) {
            job.run();
            return DONE;
        }
        try {
            asl.enqueue(job);
        } catch (Throwable t) {
            return NO_ASL;
        }
        return job.await(waitMs) ? DONE : PENDING;
    }
}
