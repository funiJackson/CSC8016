
package mini.projet_dac;

import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import static mini.projet_dac.MiniProjet_DAC.carCounterInTheStreet;
import static mini.projet_dac.MiniProjet_DAC.carNumberInTheStreet;


// [Concurrency Tech: thread-per-car (vertical road, voie 1)]
// WHY one Thread per car: each car has its own pace (vitess), its own
// queue position, and its own life cycle (queue -> intersection ->
// continue). Modeling each car as an independent Thread is the most
// natural fit and lets the carrefourManager monitor coordinate them
// via a fair ReentrantLock + Conditions instead of a hand-rolled scheduler.
public class voitureV1_V2 extends Thread {

    //<editor-fold defaultstate="collapsed" desc="Variables Declaration">
    int p;
    int vitess;
    carrefourManager gestionnaire;
    JPanel car;
    //</editor-fold>

    public voitureV1_V2(carrefourManager gestionnaire, JPanel Car, int p ,int vitess){
        this.car = Car;
        this.p = p;
        this.vitess = vitess;
        this.gestionnaire =gestionnaire;
    }

    public void run(){
        // [Concurrency Tech: AtomicInteger as cross-thread car counter]
        // carNumberInTheStreet is read by the lightManager loop predicate
        // and by the slider listeners (to size a CountDownLatch). It must
        // be visible across threads and updated atomically; AtomicInteger
        // gives us both without locking.
        carNumberInTheStreet.incrementAndGet();

        // [CONCURRENCY FIX - try/finally guarantees decrement+countDown]
        // WHY: if traversee1 throws ANY unchecked exception (NPE on the
        // panel, AIOOBE, Swing internals), the decrement and countDown
        // would be skipped. That permanently inflates carNumberInTheStreet
        // (lightManager's exit predicate never reaches 0) AND leaves the
        // producer thread parked forever on carCounterInTheStreet.await().
        // Try/finally ensures both bookkeeping ops always run, regardless
        // of how traversee1 exits.
        try {
            // All movement, light-checking, and intersection-counter work is
            // delegated to the carrefourManager monitor (verro + Conditions).
            gestionnaire.traversee1(car,p,vitess);
        } finally {
            carNumberInTheStreet.decrementAndGet();

            // [CONCURRENCY FIX - ADV-SPAWN-01: queue remove BEFORE waking producer]
            // Order matters: enqueue the panel removal on the EDT
            // FIRST, then countDown the latch. If we counted down
            // first, the producer (which polls the latch every 200ms)
            // could wake, race into createVoie*CarPanel, and call
            // invokeAndWait(add) -- arriving on the EDT EventQueue
            // BEFORE this thread's invokeLater(remove). The EDT would
            // then process [add new] before [remove old], transiently
            // inflating crossroadPanel's child count. Doing the
            // invokeLater first guarantees FIFO order: [remove old,
            // ..., add new].
            SwingUtilities.invokeLater(() -> {
                Container parent = car.getParent();
                if (parent != null) {
                    java.awt.Rectangle bounds = car.getBounds();
                    parent.remove(car);
                    parent.repaint(bounds.x, bounds.y, bounds.width, bounds.height);
                }
            });

            // [Concurrency Tech: CountDownLatch handshake with settings change]
            CountDownLatch latch = carCounterInTheStreet;
            if (latch != null)
                latch.countDown();
        }
    }
}
