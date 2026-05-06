package mini.projet_dac;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import static mini.projet_dac.MiniProjet_DAC.*;

/**
 * Concurrency monitor and movement controller.
 *
 * [Concurrency Techniques used in this class -- one entry per technique,
 *  each with WHY it is needed; see the inline [Concurrency Tech: ...]
 *  comments below for the precise call sites.]
 *
 *   1. ReentrantLock(fair) `verro`           -- monitor for shared state
 *   2. Condition `feuVertVoie1/2`            -- wait for a green light
 *   3. Condition `voie1/2_Cars_In_Intersection` -- wait for road to clear
 *   4. Condition[] `carSpacingChangedVoie1/2[p-1]` -- per-lane spacing wait
 *   5. Condition `mainRestartTimer`          -- wait for slider handoff
 *   6. Semaphore (fair) `restart`            -- STOP/START gate, FIFO drain
 *   7. AtomicBoolean `mytimerIsRunning`      -- thread-safe shadow of swing.Timer state
 *   8. AtomicIntegerArray `voit*stopPositionAtomic` -- per-lane queue positions
 *   9. SwingUtilities.invokeLater (EDT)      -- safe cross-thread Swing mutation
 *  10. Centralised `pauseIfStopped()`        -- single STOP-gate primitive
 */
public class carrefourManager {

    //<editor-fold defaultstate="collapsed" desc="Variables Declaration">

    // [CONCURRENCY FIX - P2: AtomicBoolean shadows mytimer running state]
    // The previous code called mytimer.isRunning() from the lightManager
    // thread (NOT the EDT). javax.swing.Timer is documented as not
    // thread-safe; reading its internal `running` field off-EDT is a
    // data race. We mirror the running state in an AtomicBoolean that
    // is only written from inside EDT-dispatched lambdas (where
    // mytimer.start()/stop() also live), so worker threads can safely
    // observe whether the timer is running without touching Swing
    // internals.
    static AtomicBoolean mytimerIsRunning = new AtomicBoolean(false);

    // [CONCURRENCY FIX - ADV-EDT-01: 60fps render timer]
    // WHY: previously each car submitted invokeLater(setBounds) on
    // every pixel advance (every vitess ms). With carsPerWave=5 and
    // vitess=4ms that is ~2500 EDT tasks/sec, which saturates the EDT
    // queue and makes visible position lag logical position by
    // multiple frames -- the user sees cars visually overlap during
    // high-traffic bursts even though logical j values are correctly
    // spaced >= slotSpacing apart.
    //
    // The render timer runs on the EDT every 16ms (60fps). Each tick
    // reads every active car's myJ AtomicInteger and sets its panel
    // bounds in ONE pass. EDT load is now bounded at ~60 ticks/sec
    // independent of car count or speed.
    //
    // Each car owns a RenderEntry that holds (panel, myJ ref, lane
    // coord, axis flag). The car registers it on entry and removes
    // it in its finally block. CopyOnWriteArrayList means the timer
    // can iterate without taking any lock; concurrent add/remove
    // observe a snapshot at most one tick stale.
    static class RenderEntry {
        final JPanel panel;
        final java.util.concurrent.atomic.AtomicInteger position;
        final int laneCoord;
        final boolean voie1; // true => panel moves on Y, lane coord is X
        RenderEntry(JPanel p, java.util.concurrent.atomic.AtomicInteger pos,
                    int lane, boolean v1) {
            this.panel = p; this.position = pos;
            this.laneCoord = lane; this.voie1 = v1;
        }
    }
    static final java.util.List<RenderEntry> renderRegistry =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    static final Timer renderTimer = new Timer(16, e -> {
        for (RenderEntry r : renderRegistry) {
            int pos = r.position.get();
            if (r.voie1) {
                r.panel.setBounds(r.laneCoord, pos, 30, 60);
            } else {
                r.panel.setBounds(pos, r.laneCoord, 60, 30);
            }
        }
    });
    static { renderTimer.start(); }

    static Timer mytimer = new Timer(1000, new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {

            if(!stopButtonIsActive.get()){

                seconds.decrementAndGet();
                if (seconds.get() == 0) {
                    seconds.set(duree_de_feu.get() / 1000);
                }
                lightTimer.setText(String.valueOf(seconds.get()));
            }
        }
    });

    // [Concurrency Tech: ReentrantLock(fair)]
    // WHY fair=true: every shared mutation (light state, intersection counter,
    // queue positions, spacing) goes through this single monitor, so we want
    // FIFO acquisition to avoid one thread (e.g. the light controller) being
    // starved by a constant stream of car threads.
    static Lock verro = new ReentrantLock(true);

    // [Concurrency Tech: Condition variables]
    // Each Condition encodes one specific "wait predicate" on the verro monitor:
    //   feuVertVoie1/2 -> "my road is green"
    //   voie*_Cars_In_Intersection -> "intersection is empty so light may flip"
    //   carSpacingChangedVoie1/2[p-1] -> "a car in MY specific lane moved"
    //   mainRestartTimer -> "main thread has applied new light-duration setting"
    Condition feuVertVoie1 = verro.newCondition();
    Condition feuVertVoie2 = verro.newCondition();
    Condition voie2_Cars_In_Intersection = verro.newCondition();
    Condition voie1_Cars_In_Intersection = verro.newCondition();

    // [CONCURRENCY FIX - ADV-002: per-lane spacing Conditions]
    // WHY: a single carSpacingChanged condition was signalAll'd on every
    // pixel by every car on every lane. With carsPerWave=5 and 4 lanes
    // per voie, each pixel advance woke ~10 unrelated threads who each
    // had to acquire the fair `verro` lock just to find out the wakeup
    // was meant for a different lane. Splitting per-lane scopes the
    // wakeup to threads that actually have a chance of unblocking, so a
    // voie1 lane-1 car's motion no longer disturbs voie2 lane-3 waiters.
    Condition[] carSpacingChangedVoie1 = new Condition[]{
            verro.newCondition(), verro.newCondition(),
            verro.newCondition(), verro.newCondition()};
    Condition[] carSpacingChangedVoie2 = new Condition[]{
            verro.newCondition(), verro.newCondition(),
            verro.newCondition(), verro.newCondition()};

    boolean feuVert1 = true;
    boolean feuVert2 = false;
    int nmbrVoitureIntersection = 0;

    static Condition mainRestartTimer = verro.newCondition();
    static AtomicBoolean mainStopedTheTimer = new AtomicBoolean(false);

    // [Concurrency Tech: Semaphore (fair)]
    // restart implements the STOP/START gate. STOP keeps stopButtonIsActive=true
    // and threads block on restart.acquire(); START releases enough permits to
    // let everyone drain through pauseIfStopped() once.
    static Semaphore restart = new Semaphore(0,true);

    int[] voie1PositionPossible = {420, 470, 530, 580};
    int[] voie2PositionPossible = {327, 369, 457, 500};

    // [Concurrency Tech: per-lane car registry for DYNAMIC spacing]
    // WHY this design (replaces the old voit*stopPositionAtomic equality
    // trick): every car publishes its current j position via its own
    // AtomicInteger and registers it in the list for its lane on entry,
    // deregisters on exit. Followers scan the list each frame to find
    // the closest car ahead and await on `carSpacingChanged` if that
    // car is within slotSpacing pixels. This handles:
    //   - leading car of a wave (no car ahead -> never blocks),
    //   - rear car spawned with sub-spacing gap (blocks immediately),
    //   - same-p collisions in carsPerWave>=5 (multiple cars share a
    //     lane and each contributes a position to the same list),
    //   - variable vitess (a fast car catching up a slow car re-checks
    //     spacing on every j increment).
    // All access to these lists is guarded by `verro` so plain ArrayList
    // suffices; the AtomicInteger entries provide cheap cross-thread
    // visibility for the per-frame scan.
    @SuppressWarnings("unchecked")
    java.util.List<java.util.concurrent.atomic.AtomicInteger>[] voie1LaneCars =
            new java.util.List[]{
                    new java.util.ArrayList<>(),
                    new java.util.ArrayList<>(),
                    new java.util.ArrayList<>(),
                    new java.util.ArrayList<>()};
    @SuppressWarnings("unchecked")
    java.util.List<java.util.concurrent.atomic.AtomicInteger>[] voie2LaneCars =
            new java.util.List[]{
                    new java.util.ArrayList<>(),
                    new java.util.ArrayList<>(),
                    new java.util.ArrayList<>(),
                    new java.util.ArrayList<>()};

    //</editor-fold>

    // [Concurrency Tech: EDT (Event Dispatch Thread) synchronization]
    // WHY: Swing is single-threaded. Every mutation of a component
    // (setEnabled, setText, setBounds, setIcon, add, remove) must run on
    // the EDT, otherwise we can get visual glitches, half-painted frames,
    // and (rarely) AWT-internal exceptions. Worker threads (light
    // controller, car threads) call this helper instead of touching Swing
    // directly. We use invokeLater (fire-and-forget) so the worker thread
    // never blocks the EDT and never blocks itself; it also keeps the
    // verro lock hold-time short.
    static void runOnEventDispatchThread(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    // [Concurrency Tech: Semaphore acquire as a STOP gate]
    // WHY consolidated: the original code repeated this exact try/catch in 4
    // different places (Intersection, traversee1, traversee2, circuler), which
    // duplicates concurrency code and makes auditing for bugs harder. One
    // method = one place to reason about pause/resume.
    public void pauseIfStopped() {
        try {
            if (stopButtonIsActive.get()) {
                restart.acquire();
            }
        } catch (InterruptedException ex) {
            // [CONCURRENCY FIX - P2: restore interrupt status]
            // Without this, an interrupted thread silently continues as
            // if STOP had never been requested. Preserving the flag
            // lets callers (and any further blocking calls) react.
            Thread.currentThread().interrupt();
            Logger.getLogger(carrefourManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void Intersection() {

        pauseIfStopped();

        verro.lock();
        try {
            // [CONCURRENCY FIX - P2: read mytimerIsRunning, not mytimer.isRunning()]
            // The lightManager thread (this thread) is NOT the EDT.
            // Reading swing.Timer's internal state off-EDT is a data
            // race; we use an AtomicBoolean shadow that is mutated only
            // inside EDT-dispatched lambdas alongside Timer.start/stop.
            if (mytimerIsRunning.get()) {
                // [CONCURRENCY FIX - EDT: yellow phase Swing mutations]
                // mytimer is a javax.swing.Timer (its actionPerformed runs on
                // the EDT), and the six setEnabled() calls below mutate
                // visible JLabel state. Both must be touched from the EDT.
                runOnEventDispatchThread(() -> {
                    mytimer.stop();
                    mytimerIsRunning.set(false);
                    feuVoie1Orange.setEnabled(true);
                    feuVoie2Orange.setEnabled(true);
                    feuVoie1Green.setEnabled(false);
                    feuVoie2Red.setEnabled(false);
                    feuVoie1Red.setEnabled(false);
                    feuVoie2Green.setEnabled(false);
                });
            }

            if (feuVert1) {

                feuVert1 = false;

                // [CONCURRENCY FIX - BUG 2: if -> while around await]
                // (a) await() is allowed to return on a spurious wakeup, and
                // (b) other threads can change nmbrVoitureIntersection between
                // a signal and the moment we re-acquire the lock, so a one-shot
                // 'if' check is unsafe. while() guarantees we only flip the
                // light when the intersection is truly empty.
                while (nmbrVoitureIntersection != 0) {
                    voie1_Cars_In_Intersection.await();
                }

                feuVert2 = true;
                // [CONCURRENCY FIX - EDT: switch voie1 -> voie2]
                runOnEventDispatchThread(() -> {
                    feuVoie1Orange.setEnabled(false);
                    feuVoie2Orange.setEnabled(false);
                    feuVoie1Green.setEnabled(false);
                    feuVoie2Red.setEnabled(false);
                    feuVoie1Red.setEnabled(true);
                    feuVoie2Green.setEnabled(true);
                });
                feuVertVoie2.signalAll();

            } else {

                feuVert2 = false;

                // [CONCURRENCY FIX - BUG 2: if -> while around await]
                while (nmbrVoitureIntersection != 0) {
                    voie2_Cars_In_Intersection.await();
                }

                feuVert1 = true;
                // [CONCURRENCY FIX - EDT: switch voie2 -> voie1]
                runOnEventDispatchThread(() -> {
                    feuVoie1Orange.setEnabled(false);
                    feuVoie2Orange.setEnabled(false);
                    feuVoie1Green.setEnabled(true);
                    feuVoie2Red.setEnabled(true);
                    feuVoie1Red.setEnabled(false);
                    feuVoie2Green.setEnabled(false);
                });
                feuVertVoie1.signalAll();
            }

            // [CONCURRENCY FIX - removed mainStopedTheTimer wait]
            // The slider listener used to set mainStopedTheTimer=true,
            // and only the START button handler ever signaled
            // mainRestartTimer. A slider drag with no STOP/START would
            // hang lightManager here forever, freezing the lights and
            // ultimately deadlocking the producer (cars stuck in PHASE
            // B never countDown the latch). The slider now updates
            // duree_de_feu atomically and we simply re-read it on the
            // next cycle.
            seconds.set(duree_de_feu.get() / 1000);
            // [CONCURRENCY FIX - EDT: timer text + restart]
            // setText mutates Swing; mytimer.start() schedules the next
            // EDT-side tick. Keep both on the EDT, and update the
            // mytimerIsRunning shadow flag while we're already on the
            // EDT so workers always see a consistent view.
            runOnEventDispatchThread(() -> {
                lightTimer.setText(String.valueOf(seconds.get()));
                mytimer.start();
                mytimerIsRunning.set(true);
            });

        } catch (InterruptedException ex) {
            // [CONCURRENCY FIX - P2: restore interrupt status]
            Thread.currentThread().interrupt();
            Logger.getLogger(carrefourManager.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            verro.unlock();
        }
    }

    public void traversee1(JPanel C, int p, int vitess) {
        // [CONCURRENCY FIX - dynamic per-lane spacing]
        // WHY this design: the previous voit1stopPositionAtomic equality
        // trick missed the case where a rear car spawned with a sub-80px
        // gap was never throttled before it physically overlapped the
        // front car at the stop line (user-reported visual overlap).
        // The new design publishes every car's live j position into a
        // per-lane list and has each car scan that list every frame for
        // the closest car ahead. If that car is within slotSpacing
        // pixels, this car awaits on carSpacingChanged. The leading car
        // of any wave finds nobody ahead and proceeds without blocking;
        // followers naturally maintain >= slotSpacing distance.
        //
        // Phases:
        //   PHASE A -- dynamic spacing (every frame, scan registry).
        //   PHASE B -- stop-line green-light check + ++counter.
        //   PHASE C -- (none) decrement counter on exit in `finally`.

        final int stopLine = 225;
        final int slotSpacing = 80;
        final int laneX = voie1PositionPossible[p - 1];
        boolean enteredIntersection = false;

        // [Concurrency Tech: AtomicInteger as per-car position publication]
        // Each car owns one AtomicInteger that holds its current j.
        // Followers iterate the lane registry under verro and read this
        // value lock-free; the render timer (60fps EDT timer) reads it
        // lock-free to update the visible panel bounds.
        // [CONCURRENCY FIX - ADV-PHASE-A-INITIAL-DEADLOCK]
        // Initialize to Integer.MIN_VALUE rather than -60 so peers
        // looking at us BEFORE we've passed our first PHASE A gate
        // (i.e. before myJ.set(j) is called) see a sentinel value
        // that never satisfies the spacing predicate `oj >= myJ`.
        // Otherwise two cars spawned in rapid succession on the same
        // lane both register with myJ=-60, both enter PHASE A's
        // hasCloseCar scan, both find a peer at -60 with distance 0
        // <= slotSpacing, and BOTH AWAIT carSpacingChanged forever --
        // a mutual deadlock that leaks carNumberInTheStreet count and
        // ultimately stalls spawning entirely.
        java.util.concurrent.atomic.AtomicInteger myJ =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);

        // [CONCURRENCY FIX - ADV-EDT-01: register with render timer]
        // The render timer (16ms tick on EDT) reads myJ every frame and
        // moves this panel to the correct position. NO per-pixel
        // invokeLater is needed -- this is the entire visual pipeline.
        // Note: until myJ.set(-60) runs in the first PHASE A pass,
        // the timer paints the panel at y=Integer.MIN_VALUE, which is
        // off-screen and harmless.
        RenderEntry renderEntry = new RenderEntry(C, myJ, laneX, true);
        renderRegistry.add(renderEntry);

        verro.lock();
        try {
            voie1LaneCars[p - 1].add(myJ);
        } finally {
            verro.unlock();
        }

        try {
            for (int j = -60; j < 830; j++) {
                pauseIfStopped();

                // PHASE A: dynamic spacing -- always-on, scan registry.
                // [CONCURRENCY FIX - ADV-OVERLAP-01]
                // myJ.set(j) MUST happen INSIDE the verro-held block,
                // AFTER spacing is confirmed safe. The previous version
                // published myJ before the gate, allowing two same-lane
                // cars whose threads were scheduled close together to
                // both stamp myJ=K simultaneously and BOTH pass PHASE A
                // because the predicate `oj > myJ` returns false for
                // equal values. Result: two cars logically at j=K
                // (visual overlap on render). The fix is to publish the
                // new j only after PHASE A confirms no peer is within
                // slotSpacing pixels ahead OR at exactly this pixel.
                // [Concurrency Tech: per-lane Condition + registry scan under verro]
                verro.lock();
                try {
                    while (hasCloseCarAheadVoie1(myJ, p, j, slotSpacing)) {
                        carSpacingChangedVoie1[p - 1].await();
                    }
                    // Position is now confirmed safe -- publish it.
                    myJ.set(j);
                } finally {
                    verro.unlock();
                }

                // PHASE B: stop-line green-light check.
                // [CONCURRENCY FIX - red-light running fixed]
                // [Concurrency Tech: Condition (feuVertVoie1) + monitor counter]
                if (j == stopLine && !enteredIntersection) {
                    verro.lock();
                    try {
                        while (!feuVert1) {
                            feuVertVoie1.await();
                        }
                        nmbrVoitureIntersection++;
                        enteredIntersection = true;
                    } finally {
                        verro.unlock();
                    }
                }

                // No per-pixel setBounds: the render timer picks up
                // myJ's new value on its next 16ms tick.
                Thread.sleep(vitess);

                // [Concurrency Tech: per-lane Condition signalAll on motion]
                verro.lock();
                try {
                    carSpacingChangedVoie1[p - 1].signalAll();
                } finally {
                    verro.unlock();
                }
            }

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Logger.getLogger(carrefourManager.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            // Deregister from render timer FIRST so the timer never
            // reads myJ for a car that is also being removed from
            // voie1LaneCars below.
            renderRegistry.remove(renderEntry);

            // [CONCURRENCY FIX - P1#10: bookkeeping runs in finally]
            verro.lock();
            try {
                voie1LaneCars[p - 1].remove(myJ);
                if (enteredIntersection) {
                    nmbrVoitureIntersection--;
                    // [CONCURRENCY FIX - ADV-LIVENESS-01]
                    // Signal BOTH intersection-cleared Conditions when
                    // count hits 0, ignoring whose light is green.
                    // The old code signaled only voie1_Cars_In_Intersection
                    // guarded by `!feuVert1`, assuming "the last car to
                    // exit belongs to the voie whose green is being
                    // switched off." That fails for slow voie1 cars
                    // whose traversal spans 2+ light cycles: by the time
                    // the slow car exits, lightManager may already be
                    // awaiting voie2_Cars_In_Intersection. Signaling
                    // only voie1's condition leaves lightManager parked
                    // forever -> light cycle freezes -> all PHASE-B
                    // cars never wake -> finally never runs -> render
                    // registry leaks unboundedly -> EDT saturates ->
                    // producer's invokeAndWait stalls -> spawn stops.
                    // signalAll on an empty wait queue is a no-op, and
                    // both Intersection() awaits are inside while-loops
                    // so spurious wakeups are handled.
                    if (nmbrVoitureIntersection == 0) {
                        voie1_Cars_In_Intersection.signalAll();
                        voie2_Cars_In_Intersection.signalAll();
                    }
                }
                carSpacingChangedVoie1[p - 1].signalAll();
            } finally {
                verro.unlock();
            }
        }
    }

    // Helper: scan voie1 lane registry for any peer car ahead within
    // `spacing` pixels (or AT my exact pixel). Must be called with
    // verro held. Self-exclusion is by REFERENCE EQUALITY against the
    // caller's own AtomicInteger (mySelf), because we use `>=` so that
    // two cars at the same logical j are also treated as "close" --
    // the strict `>` previously let same-j cars slip past each other
    // (ADV-OVERLAP-01).
    private boolean hasCloseCarAheadVoie1(java.util.concurrent.atomic.AtomicInteger mySelf,
                                          int p, int myJ, int spacing) {
        for (java.util.concurrent.atomic.AtomicInteger other : voie1LaneCars[p - 1]) {
            if (other == mySelf) continue;  // never block on self
            int oj = other.get();
            if (oj >= myJ && oj - myJ <= spacing) {
                // Distance <= slotSpacing (or zero == same pixel) means
                // we are tail-gating; the rear car must wait until the
                // front advances out of range.
                return true;
            }
        }
        return false;
    }

    public void traversee2(JPanel C, int p, int vitess) {
        // [CONCURRENCY FIX - dynamic per-lane spacing]
        // Symmetric to traversee1 but on the x axis. See traversee1 for
        // the full rationale of the per-lane registry design.

        final int stopLine = 310;
        final int slotSpacing = 80;
        final int laneY = voie2PositionPossible[p - 1];
        boolean enteredIntersection = false;

        // [CONCURRENCY FIX - ADV-PHASE-A-INITIAL-DEADLOCK]
        // See traversee1 for the full rationale: Integer.MIN_VALUE
        // sentinel prevents two newly-spawned same-lane cars from
        // mutually deadlocking before either has passed PHASE A.
        java.util.concurrent.atomic.AtomicInteger myJ =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);

        // [CONCURRENCY FIX - ADV-EDT-01: register with render timer]
        RenderEntry renderEntry = new RenderEntry(C, myJ, laneY, false);
        renderRegistry.add(renderEntry);

        verro.lock();
        try {
            voie2LaneCars[p - 1].add(myJ);
        } finally {
            verro.unlock();
        }

        try {
            for (int j = -60; j < 1035; j++) {
                pauseIfStopped();

                // PHASE A: dynamic spacing -- always-on
                // [CONCURRENCY FIX - ADV-OVERLAP-01: myJ.set INSIDE the
                //  PHASE A critical section, after spacing is confirmed]
                verro.lock();
                try {
                    while (hasCloseCarAheadVoie2(myJ, p, j, slotSpacing)) {
                        carSpacingChangedVoie2[p - 1].await();
                    }
                    myJ.set(j);
                } finally {
                    verro.unlock();
                }

                // PHASE B: stop-line green-light check + counter
                if (j == stopLine && !enteredIntersection) {
                    verro.lock();
                    try {
                        while (!feuVert2) {
                            feuVertVoie2.await();
                        }
                        nmbrVoitureIntersection++;
                        enteredIntersection = true;
                    } finally {
                        verro.unlock();
                    }
                }

                // Render timer picks up myJ on next 16ms tick; no per-pixel EDT submit.
                Thread.sleep(vitess);

                // Per-lane signal: wake only same-lane rear waiters.
                verro.lock();
                try {
                    carSpacingChangedVoie2[p - 1].signalAll();
                } finally {
                    verro.unlock();
                }
            }

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Logger.getLogger(carrefourManager.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            renderRegistry.remove(renderEntry);
            verro.lock();
            try {
                voie2LaneCars[p - 1].remove(myJ);
                if (enteredIntersection) {
                    nmbrVoitureIntersection--;
                    // [CONCURRENCY FIX - ADV-LIVENESS-01]
                    // Symmetric to traversee1: signal BOTH Conditions
                    // when count hits 0. See traversee1 finally for
                    // the full rationale.
                    if (nmbrVoitureIntersection == 0) {
                        voie1_Cars_In_Intersection.signalAll();
                        voie2_Cars_In_Intersection.signalAll();
                    }
                }
                carSpacingChangedVoie2[p - 1].signalAll();
            } finally {
                verro.unlock();
            }
        }
    }

    // Helper: same as voie1 -- self-exclusion by reference, predicate `>=`.
    private boolean hasCloseCarAheadVoie2(java.util.concurrent.atomic.AtomicInteger mySelf,
                                          int p, int myJ, int spacing) {
        for (java.util.concurrent.atomic.AtomicInteger other : voie2LaneCars[p - 1]) {
            if (other == mySelf) continue;
            int oj = other.get();
            if (oj >= myJ && oj - myJ <= spacing) {
                return true;
            }
        }
        return false;
    }
}
