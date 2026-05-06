
package mini.projet_dac;

import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import static mini.projet_dac.MiniProjet_DAC.carCounterInTheStreet;
import static mini.projet_dac.MiniProjet_DAC.carNumberInTheStreet;


// [Concurrency Tech: thread-per-car (horizontal road, voie 2)]
// WHY one Thread per car: see voitureV1_V2 for the full rationale; this
// is the symmetric class for voie 2, delegating coordination to the
// shared carrefourManager monitor.
public class voitureV2_V1 extends Thread{

    //<editor-fold defaultstate="collapsed" desc="Variables Declaration">
    JPanel car;
    int p;
    int vitess;
    carrefourManager gestionnaire;
    //</editor-fold>

    public voitureV2_V1(carrefourManager gestionnaire, JPanel Car, int p ,int vitess){
        this.car = Car;
        this.p = p;
        this.vitess = vitess;
        this.gestionnaire =gestionnaire;
    }

    public void run(){
        // [Concurrency Tech: AtomicInteger cross-thread car counter]
        carNumberInTheStreet.incrementAndGet();

        // [CONCURRENCY FIX - try/finally guarantees decrement+countDown]
        // Symmetric to voitureV1_V2.run(). Without try/finally, an
        // unchecked exception from traversee2 would skip both the
        // counter decrement and the latch countDown, deadlocking the
        // lightManager exit predicate AND the producer's
        // carCounterInTheStreet.await().
        try {
            // Movement + light-check + intersection counters all happen
            // inside the carrefourManager monitor (verro + Conditions).
            gestionnaire.traversee2(car,p,vitess);
        } finally {
            carNumberInTheStreet.decrementAndGet();

            // [CONCURRENCY FIX - ADV-SPAWN-01 + panel cleanup]
            // Symmetric to voitureV1_V2: enqueue remove BEFORE the
            // latch countDown so EDT processes [remove old] before
            // [add new] from the woken producer.
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
