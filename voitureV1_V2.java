
package mini.projet_dac;

import java.util.concurrent.CountDownLatch;
import javax.swing.JPanel;
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

            // [Concurrency Tech: CountDownLatch handshake with settings change]
            // When the user moves the speed/light-duration sliders, the
            // producer thread allocates a CountDownLatch sized to the cars
            // currently on the street and then awaits it. Each car that was
            // already on the street counts down on exit, so the new setting
            // is applied only after this generation has cleared the road.
            // The null-check covers cars created before any slider change.
            // Read the field once to avoid a TOCTOU NPE if the EDT reassigns
            // it between the null-check and the countDown call.
            CountDownLatch latch = carCounterInTheStreet;
            if (latch != null)
                latch.countDown();
        }
    }
}
