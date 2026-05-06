
package mini.projet_dac;


import java.util.logging.Level;
import java.util.logging.Logger;
import static mini.projet_dac.MiniProjet_DAC.duree_de_feu;
import static mini.projet_dac.MiniProjet_DAC.stopButtonIsActive;


// [Concurrency Tech: dedicated traffic-light controller thread]
// WHY a separate thread (not a javax.swing.Timer): the controller has to
// AWAIT on a Condition (voie*_Cars_In_Intersection) until the road has
// emptied, before it is allowed to flip the light. That await blocks the
// caller, and we must NEVER block the EDT. So light orchestration runs on
// its own worker thread; only the actual Swing mutations (setEnabled on
// JLabels) are dispatched to the EDT via carrefourManager.runOnEventDispatchThread.
public class lightManager extends Thread {

    carrefourManager gestionnaire;


    public lightManager(carrefourManager gestionnaire){

        this.gestionnaire = gestionnaire;

    }

    @Override
    public void run(){

        // [CONCURRENCY FIX - P1#5: lightManager never exits naturally]
        // The previous predicate `while(!stopButtonIsActive.get() ||
        // carNumberInTheStreet.get()!=0)` could go false (STOP active
        // AND no cars on street) and terminate the controller thread.
        // Subsequent STARTs found `carrefour != null` and never created
        // a replacement, leaving the simulation with no light controller
        // and one road permanently red. By looping forever and relying
        // on Intersection() -> pauseIfStopped() to block during STOP,
        // the same lightManager instance survives any number of
        // STOP/START cycles. A separate flag (Thread.interrupt or app
        // shutdown) is the right shutdown mechanism.
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Intersection() handles its own pauseIfStopped() at
                // entry, so STOP correctly suspends the light cycle
                // without us polling here.
                gestionnaire.Intersection();

                // [CONCURRENCY FIX - P2: chunked sleep + STOP responsiveness]
                // Re-read duree_de_feu each chunk so a slider change
                // takes effect within ~100ms instead of waiting out the
                // full prior cycle. STOP also breaks the wait early.
                int remaining = duree_de_feu.get();
                while (remaining > 0 && !stopButtonIsActive.get()
                        && !Thread.currentThread().isInterrupted()) {
                    int chunk = Math.min(100, remaining);
                    sleep(chunk);
                    remaining -= chunk;
                }
            } catch (InterruptedException ex) {
                // [CONCURRENCY FIX - ADV-GHOST-LANE-02]
                // Do NOT re-set the interrupt flag here: re-setting it
                // makes the next while(!isInterrupted()) check exit the
                // loop, killing the only light controller. That used to
                // trigger creationNewCars() to rebuild the carrefour,
                // splitting the lane registries between old and new
                // cars and breaking PHASE A spacing entirely. Just log
                // and continue the control loop. The application JFrame
                // sets EXIT_ON_CLOSE so process termination on window
                // close is the legitimate shutdown path.
                Logger.getLogger(lightManager.class.getName())
                        .log(Level.WARNING, "lightManager interrupted, continuing", ex);
            }
        }
    }
}
