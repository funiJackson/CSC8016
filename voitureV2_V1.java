
package mini.projet_dac;

import java.util.concurrent.CountDownLatch;
import javax.swing.JPanel;
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

            // [Concurrency Tech: CountDownLatch handshake with settings change]
            // Counting down lets the producer thread know one more "old"
            // car has cleared the street, so a pending speed /
            // light-duration change can be applied once the generation
            // has fully drained. Snapshot the field to avoid a TOCTOU
            // NPE if the EDT reassigns it between the null check and
            // countDown().
            CountDownLatch latch = carCounterInTheStreet;
            if (latch != null)
                latch.countDown();
        }
    }
}
