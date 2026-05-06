package mini.projet_dac;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import static java.lang.Thread.sleep;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.lang.reflect.InvocationTargetException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import static mini.projet_dac.carrefourManager.*; 

public class MiniProjet_DAC extends JFrame {
    
    //<editor-fold defaultstate="collapsed" desc="Variables Declaration">
    carrefourManager carrefour;          //carrefour manager
    lightManager changeFeu;             //light manager
    Thread createCars;                    //thread that manages the creation of the cars
    //Swing components for the graphic interface
    private JPanel containerPanel;                                              
    private JPanel settingPanel;
    private JButton stopButton;
    private JButton startButton;
    private JLabel timerLabel;
    static JLabel lightTimer;
    private JLabel tittleLabel;
    private JLabel speedLabel;
    private JLabel trafficLabel;
    private JLabel lightDurationLabel;
    private JLabel carCountLabel;
    private JSlider speedSlider;
    private JSlider trafficGrowthSlider;
    private JSlider lightDurationSlider;
    private JSlider carCountSlider;
    static JLabel feuVoie1Green;
    static JLabel feuVoie1Orange;
    static JLabel feuVoie1Red;
    private JPanel feuVoie1Panel;
    static JLabel feuVoie2Green;
    static JLabel feuVoie2Orange;
    static JLabel feuVoie2Red;
    private JPanel feuVoie2Panel;
    private backgroundPanel crossroadPanel;
    // [Concurrency Tech: Atomic variables for cross-thread state]
    // WHY atomics (not plain int/boolean): every field below is read and
    // written from at least two threads -- the EDT (slider/button
    // handlers), the producer thread (createCars), the lightManager
    // thread, and N car worker threads. Using AtomicInteger /
    // AtomicBoolean gives us cheap, lock-free, memory-visible publication
    // for these shared scalars; the heavier verro lock is reserved for
    // the multi-field state that must change together (light booleans,
    // intersection counter, queue positions).
    static AtomicInteger duree_de_feu = new AtomicInteger(10000);     //duree between (5000-->5s , 20000-->20s)
    // [CONCURRENCY FIX - P2 visibility]
    // speed / circulationGrow / carsPerWave are written on the EDT and
    // read by the producer thread. Plain int reads on a worker thread
    // are NOT guaranteed to observe the EDT's writes by the JMM. Marking
    // them volatile establishes a happens-before edge on every read so
    // the producer always sees the latest slider value.
    volatile int speed = 4;   //speed between (1-8)
    volatile int circulationGrow = (new Random().nextInt(8) + 1) * 1000;       // traffic between (1000-->1s, 8000-->8s)
    volatile int carsPerWave = 3;  //number of cars created in each direction every traffic interval
    static AtomicInteger seconds = new AtomicInteger(duree_de_feu.get() / 1000);//seconds for the timer counter
    AtomicBoolean settingChanged = new AtomicBoolean(false);      //slider listeners flip this so the producer knows to wait on the latch
    static AtomicInteger carNumberInTheStreet = new AtomicInteger(0);   //counter incremented/decremented by car threads, read by lightManager + slider listeners
    // [Concurrency Tech: CountDownLatch settings handoff]
    // Allocated by a slider listener with carNumberInTheStreet as count;
    // each car counts down on exit; producer awaits zero before applying
    // the new setting.
    // [CONCURRENCY FIX - P2 visibility]
    // `volatile` is required: the EDT slider listeners reassign the
    // reference (lines below). Without volatile, the producer thread
    // and car threads have NO happens-before guarantee that they see
    // the new latch reference -- they could observe null or a stale
    // already-counted-down latch and either NPE or skip the handshake.
    static volatile CountDownLatch carCounterInTheStreet;
    static AtomicBoolean stopButtonIsActive = new AtomicBoolean(true);    //flipped by STOP/START buttons on EDT, polled by all worker threads
    
     //</editor-fold>
    
    public MiniProjet_DAC() {
        
        //<editor-fold defaultstate="collapsed" desc="initialisation of the swing components and configueration of there layout">
        containerPanel = new JPanel();
        settingPanel = new JPanel();
        crossroadPanel = new backgroundPanel();
        feuVoie2Panel = new JPanel();
        feuVoie2Red = new JLabel();
        feuVoie2Orange = new JLabel();
        feuVoie2Green = new JLabel();
        feuVoie1Panel = new JPanel();
        feuVoie1Red = new JLabel();
        feuVoie1Orange = new JLabel();
        feuVoie1Green = new JLabel();
        timerLabel = new JLabel();
        lightTimer = new JLabel();
        tittleLabel = new JLabel();
        speedLabel = new JLabel();
        speedSlider = new JSlider();
        trafficLabel = new JLabel();
        trafficGrowthSlider = new JSlider();
        lightDurationSlider = new JSlider();
        lightDurationLabel = new JLabel();
        carCountLabel = new JLabel();
        carCountSlider = new JSlider();
        stopButton = new JButton();
        startButton = new JButton();
        
        crossroadPanel.setLayout(null);
        crossroadPanel.setBounds(0, 0, 1035, 840);
        
        
        feuVoie2Panel.setLayout(null);

        feuVoie2Red.setBackground(new java.awt.Color(255, 255, 255));
        feuVoie2Red.setBounds(0, 0, 35, 30);
        feuVoie2Red.setIcon(new javax.swing.ImageIcon("lights/1.png"));
        // [CONCURRENCY FIX - P2: initial light state matches monitor]
        // feuVert2 starts FALSE, so voie2 must start visually RED.
        feuVoie2Red.setEnabled(true);
        feuVoie2Panel.add(feuVoie2Red);
        feuVoie2Orange.setBounds(35, 0, 35, 30);
        feuVoie2Orange.setIcon(new javax.swing.ImageIcon("lights/3.jpg"));
        feuVoie2Orange.setEnabled(false);
        feuVoie2Panel.add(feuVoie2Orange);
        feuVoie2Green.setBounds(70, 0, 35, 30);
        feuVoie2Green.setIcon(new javax.swing.ImageIcon("lights/2.jpg"));
        feuVoie2Green.setEnabled(false);
        feuVoie2Panel.add(feuVoie2Green);
        
        feuVoie2Panel.setBounds(270, 540, 105, 30);
        crossroadPanel.add(feuVoie2Panel);

        feuVoie1Panel.setLayout(null);
        
        feuVoie1Red.setBounds(0, 0, 30, 35);
        feuVoie1Red.setIcon(new javax.swing.ImageIcon("lights/1*.png"));
        feuVoie1Red.setEnabled(false);
        feuVoie1Panel.add(feuVoie1Red);
        feuVoie1Orange.setBounds(0, 35, 30, 35);
        feuVoie1Orange.setIcon(new javax.swing.ImageIcon("lights/3*.jpg"));
        feuVoie1Orange.setEnabled(false);
        feuVoie1Panel.add(feuVoie1Orange);
        feuVoie1Green.setBounds(0, 70, 30, 35);
        feuVoie1Green.setIcon(new javax.swing.ImageIcon("lights/2*.jpg"));
        // [CONCURRENCY FIX - P2: initial light state matches monitor]
        // carrefourManager.feuVert1 starts as TRUE, so the visual light
        // for voie1 must start GREEN to match. The original code had
        // ALL labels disabled, which made the first cycle look like
        // cars were running a red light even though the monitor's
        // logical state correctly admitted them.
        feuVoie1Green.setEnabled(true);
        feuVoie1Panel.add(feuVoie1Green);
        
        feuVoie1Panel.setBounds(630, 180, 30, 105);
        crossroadPanel.add(feuVoie1Panel);

        
        containerPanel.add(crossroadPanel);

        settingPanel.setBackground(new java.awt.Color(255, 255, 255));
        settingPanel.setLayout(null);

        timerLabel.setFont(new java.awt.Font("Chalkboard SE", 0, 36)); // NOI18N
        timerLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        timerLabel.setText("Timer :");
        timerLabel.setBounds(50, 110, 120, 50);
        settingPanel.add(timerLabel);

        lightTimer.setFont(new java.awt.Font("Myanmar MN", 0, 36)); // NOI18N
        lightTimer.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lightTimer.setBackground(new java.awt.Color(255, 255, 255));
        lightTimer.setForeground(new java.awt.Color(255, 0, 51));
        lightTimer.setBorder(new javax.swing.border.LineBorder(new java.awt.Color(0, 0, 0), 1, true));
        lightTimer.setOpaque(true);
        lightTimer.setText(String.valueOf(seconds.get()));
        lightTimer.setBounds(200, 110, 120, 50);
        settingPanel.add(lightTimer);

        tittleLabel.setFont(new java.awt.Font("Chalkboard SE", 0, 36)); // NOI18N
        tittleLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        tittleLabel.setText("SETTINGS :");
        tittleLabel.setBounds(10, 10, 400, 70);
        settingPanel.add(tittleLabel);

        speedLabel.setFont(new java.awt.Font("Chalkboard SE", 0, 24)); // NOI18N
        speedLabel.setText("Speed :");
        speedLabel.setBounds(10, 200, 150, 50);
        settingPanel.add(speedLabel);

        speedSlider.setBackground(new java.awt.Color(230, 230, 230));
        speedSlider.setForeground(new java.awt.Color(0, 0, 0));
        speedSlider.setValue(speed);
        speedSlider.setMinimum(1);
        speedSlider.setMaximum(8);
        speedSlider.setMajorTickSpacing(1);
        speedSlider.setMinorTickSpacing(1);
        speedSlider.setPaintLabels(true);
        speedSlider.setPaintTicks(true);
        speedSlider.setSnapToTicks(true);
        speedSlider.setOpaque(true);
        speedSlider.addChangeListener(new ChangeListener() {
            // [Concurrency Tech: EDT -> producer handshake via Atomic + CountDownLatch]
            // Runs on the EDT.
            // [CONCURRENCY FIX - P2: ordering of latch publish vs flag]
            // Allocate the new latch FIRST, then set settingChanged=true.
            // The volatile-write of settingChanged then serves as the
            // release fence; any thread that observes settingChanged==
            // true is guaranteed (by JMM) to also see the new latch
            // reference. The previous order (set flag, then assign
            // latch) gave no such guarantee.
            public void stateChanged(ChangeEvent event) {
                if (!settingChanged.get()) {
                    carCounterInTheStreet = new CountDownLatch(carNumberInTheStreet.get());
                    settingChanged.set(true);
                }
                speed = 8 - speedSlider.getValue() + 1;
            }
        });
        speedSlider.setBounds(10, 260, 380, 50);
        settingPanel.add(speedSlider);

        trafficLabel.setFont(new java.awt.Font("Chalkboard SE", 0, 24)); // NOI18N
        trafficLabel.setText("Traffic Growth :");
        trafficLabel.setBounds(10, 320, 200, 50);
        settingPanel.add(trafficLabel);

        trafficGrowthSlider.setBackground(new java.awt.Color(230, 230, 230));
        trafficGrowthSlider.setForeground(new java.awt.Color(0, 0, 0));
        trafficGrowthSlider.setValue(circulationGrow / 1000);
        trafficGrowthSlider.setMaximum(8);
        trafficGrowthSlider.setMinimum(1);
        trafficGrowthSlider.setMajorTickSpacing(1);
        trafficGrowthSlider.setMinorTickSpacing(1);
        trafficGrowthSlider.setPaintLabels(true);
        trafficGrowthSlider.setPaintTicks(true);
        trafficGrowthSlider.setSnapToTicks(true);
        trafficGrowthSlider.setOpaque(true);
        trafficGrowthSlider.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent event) {
                circulationGrow = trafficGrowthSlider.getValue() * 1000;//resetting the waited time before entring new cars to the street
            }
        });
        trafficGrowthSlider.setBounds(10, 380, 380, 50);
        settingPanel.add(trafficGrowthSlider);

        lightDurationSlider.setBackground(new java.awt.Color(230, 230, 230));
        lightDurationSlider.setForeground(new java.awt.Color(0, 0, 0));
        lightDurationSlider.setValue(duree_de_feu.get() / 1000);
        lightDurationSlider.setMaximum(20);
        lightDurationSlider.setMinimum(5);
        lightDurationSlider.setMajorTickSpacing(1);
        lightDurationSlider.setMinorTickSpacing(1);
        lightDurationSlider.setPaintLabels(true);
        lightDurationSlider.setPaintTicks(true);
        lightDurationSlider.setSnapToTicks(true);
        lightDurationSlider.setOpaque(true);
        lightDurationSlider.addChangeListener(new ChangeListener() {
            // [CONCURRENCY FIX - slider-induced producer deadlock]
            // The previous version set mainStopedTheTimer=true which
            // parked lightManager on mainRestartTimer.await(). The only
            // place that signals mainRestartTimer is creationNewCars()
            // (the START button handler), so a slider drag with no
            // STOP/START would freeze the light controller, hang the
            // queue at PHASE B, prevent cars from exiting traversee,
            // and the producer would await the CountDownLatch forever
            // -- no new cars would ever spawn.
            //
            // The fix is to NOT use the handshake at all. lightManager's
            // chunked sleep re-reads duree_de_feu every 100ms (and
            // re-evaluates it at the top of every Intersection() cycle),
            // so a new value naturally takes effect within one cycle.
            // We still use the latch to delay applying the new setting
            // to fresh cars, but the light cycle is independent now.
            public void stateChanged(ChangeEvent event) {
                if (!settingChanged.get()) {
                    carCounterInTheStreet = new CountDownLatch(carNumberInTheStreet.get());
                    settingChanged.set(true);
                }
                duree_de_feu.set(lightDurationSlider.getValue() * 1000);
                // mainStopedTheTimer is no longer set here -- the chunked
                // lightManager sleep + re-read picks up duree_de_feu
                // changes on its own.
            }
        });
        lightDurationSlider.setBounds(10, 510, 380, 50);
        settingPanel.add(lightDurationSlider);

        lightDurationLabel.setFont(new java.awt.Font("Chalkboard SE", 0, 24)); // NOI18N
        lightDurationLabel.setText("Light Duration :");
        lightDurationLabel.setBounds(10, 440, 200, 50);
        settingPanel.add(lightDurationLabel);

        carCountLabel.setFont(new java.awt.Font("Chalkboard SE", 0, 24)); // NOI18N
        carCountLabel.setText("Number of Cars :");
        carCountLabel.setBounds(10, 570, 220, 50);
        settingPanel.add(carCountLabel);

        carCountSlider.setBackground(new java.awt.Color(230, 230, 230));
        carCountSlider.setForeground(new java.awt.Color(0, 0, 0));
        carCountSlider.setValue(carsPerWave);
        carCountSlider.setMaximum(5);
        carCountSlider.setMinimum(1);
        carCountSlider.setMajorTickSpacing(1);
        carCountSlider.setMinorTickSpacing(1);
        carCountSlider.setPaintLabels(true);
        carCountSlider.setPaintTicks(true);
        carCountSlider.setSnapToTicks(true);
        carCountSlider.setOpaque(true);
        carCountSlider.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent event) {
                carsPerWave = carCountSlider.getValue();
            }
        });
        carCountSlider.setBounds(10, 630, 380, 50);
        settingPanel.add(carCountSlider);

        stopButton.setFont(new java.awt.Font("Chalkboard SE", 0, 18)); // NOI18N
        stopButton.setText("STOP");
        stopButton.addActionListener(new ActionListener() {
            // [Concurrency Tech: AtomicBoolean as the STOP gate flag]
            // Runs on the EDT. Worker threads (cars, lightManager, the
            // producer) check stopButtonIsActive at every safe point and
            // call pauseIfStopped(), which acquires the `restart`
            // Semaphore. Setting this AtomicBoolean here is the
            // single-writer source of truth for "we are paused".
            public void actionPerformed(ActionEvent arg0) {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                stopButtonIsActive.set(true);
            }
        });
        stopButton.setBounds(210, 740, 130, 50);
        settingPanel.add(stopButton);
        
        startButton.setFont(new java.awt.Font("Chalkboard SE", 0, 18)); // NOI18N
        startButton.setText("START");
        startButton.addActionListener(new ActionListener() {
            
            public void actionPerformed(ActionEvent arg0) { 
                //restarting the cars after the start button pressed
                creationNewCars(arg0);
            }
        });
        settingPanel.add(startButton);
        startButton.setBounds(50, 740, 130, 50);

        settingPanel.setBounds(1035, 0, 400, 840);
        containerPanel.add(settingPanel);

        containerPanel.setLayout(null);
        containerPanel.setBounds(0, 0, 1440, 840);
        
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setBounds(0, 0, 1440, 830);
        add(containerPanel);
         //</editor-fold>
    }
    
    // [Concurrency Tech: EDT synchronization with invokeAndWait]
    // WHY: the producer thread is about to launch a car worker thread that
    // will immediately read/write the panel (e.g. C.getBounds(), setBounds).
    // The panel must be fully constructed AND attached to crossroadPanel
    // BEFORE the worker starts, otherwise the worker can race on a panel
    // that is not yet a child of any container (NullPointerException on
    // repaint, or invisible cars). invokeAndWait blocks the producer
    // thread until the EDT has finished the install, giving us a clean
    // happens-before edge between "panel is mounted" and "worker starts".
    static void runOnEventDispatchThreadAndWait(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException ex) {
                Logger.getLogger(MiniProjet_DAC.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    // [Concurrency Tech: EDT-safe panel install for voie 1]
    // Image I/O is left off the EDT (constructor only reads a PNG); only
    // the Swing-touching steps (setOpaque, setBounds, add) are dispatched
    // to the EDT so the EDT stays responsive.
    private imgVoitureVoie1 createVoie1CarPanel() {
        final imgVoitureVoie1 c = new imgVoitureVoie1();
        runOnEventDispatchThreadAndWait(() -> {
            c.setOpaque(true);
            c.setBounds(0, -60, 30, 60);
            crossroadPanel.add(c);
        });
        return c;
    }

    // [Concurrency Tech: EDT-safe panel install for voie 2]
    private imgVoitureVoie2 createVoie2CarPanel() {
        final imgVoitureVoie2 c = new imgVoitureVoie2();
        runOnEventDispatchThreadAndWait(() -> {
            c.setOpaque(true);
            c.setBounds(-60, 0, 60, 30);
            crossroadPanel.add(c);
        });
        return c;
    }

    public void creationNewCars(ActionEvent e){

        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        // [CONCURRENCY FIX - P1#6: Semaphore permit hygiene]
        // WHY drain BEFORE release: the previous code only released
        // permits on START and never drained on STOP. Across multiple
        // STOP/START cycles, leaked permits accumulated and let some
        // threads sail through pauseIfStopped() without actually
        // pausing. drainPermits() is idempotent and zeros the gate.
        restart.drainPermits();

        // [CONCURRENCY FIX - ADV-GHOST-LANE-02: NEVER rebuild carrefour]
        // Rebuilding the carrefourManager would orphan all live car
        // threads: their `gestionnaire` field still references the old
        // instance, so they would register into the OLD voie*LaneCars
        // lists, while new cars register into the NEW lists. PHASE A
        // scans only its own carrefour's lists -- old and new cars
        // would be mutually invisible, defeating spacing entirely on
        // shared lanes.
        // The carrefourManager is a long-lived singleton for the
        // application session. Only the lightManager thread is
        // recreated when it dies (which should never happen now that
        // its catch-block does not re-set the interrupt flag, but the
        // isAlive() check is defensive).
        if (carrefour == null) {
            carrefour = new carrefourManager();
        }
        if (changeFeu == null || !changeFeu.isAlive()) {
            // [Concurrency Tech: dedicated lightManager Thread]
            changeFeu = new lightManager(carrefour);
            changeFeu.start();
        }

        // [Concurrency Tech: AtomicBoolean publishes "we are running again"]
        // Order: clear the flags FIRST, THEN release permits. A thread
        // unblocked from restart.acquire() will re-check
        // stopButtonIsActive in pauseIfStopped() -- if we released
        // first, it could see stopButtonIsActive==true and immediately
        // re-acquire (deadlock until next STOP/START).
        stopButtonIsActive.set(false);
        mainStopedTheTimer.set(false);

        // [CONCURRENCY FIX - P1#6: precise permit release]
        // Release exactly one permit per actually-parked worker:
        //   - one per car thread (carNumberInTheStreet),
        //   - +1 for lightManager IF it is alive,
        //   - +1 for the producer IF it is alive.
        // The original "+ 2" assumed lightManager and producer were
        // always parked, which is not true on first START or after a
        // worker died.
        int parked = carNumberInTheStreet.get();
        int extras = (changeFeu != null && changeFeu.isAlive() ? 1 : 0)
                   + (createCars != null && createCars.isAlive() ? 1 : 0);
        restart.release(parked + extras);

        // [CONCURRENCY FIX - P1#9: producer-thread mainRestartTimer signal race]
        // The original creationNewCars set mainStopedTheTimer.set(false)
        // on the EDT, then the producer thread separately checked the
        // SAME flag at the top of its slider-applied block. If the EDT
        // cleared the flag first, the producer skipped the
        // mainRestartTimer.signal() block, leaving the lightManager
        // permanently parked on mainRestartTimer.await(). The fix is
        // to ALWAYS signal mainRestartTimer here (under verro), so the
        // lightManager wakes regardless of who won the EDT-vs-producer
        // race. Signal is idempotent: if the lightManager was not in
        // fact waiting, the signal is harmlessly lost.
        verro.lock();
        try {
            mainRestartTimer.signalAll();
        } finally {
            verro.unlock();
        }

        if (createCars == null || !createCars.isAlive()) {

            // [Concurrency Tech: producer thread spawning car threads]
            // WHY a separate thread (not the EDT): this loop has to
            // sleep(circulationGrow) and await on the CountDownLatch
            // when settings change. Either of those would freeze the UI
            // if run on the EDT. The producer ONLY touches Swing through
            // createVoie*CarPanel, which dispatches to the EDT.
            createCars = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        // [CONCURRENCY FIX - ADV-SPAWN-02: drain stale interrupt flag]
                        // WHY: runOnEventDispatchThreadAndWait catches
                        // InterruptedException from invokeAndWait and
                        // re-sets the interrupt flag (preserving caller's
                        // interrupt status -- standard pattern). If a
                        // spurious interrupt arrives (GC pause, OS
                        // signal, JVM internals) during a producer's
                        // createVoie*CarPanel call, the flag returns to
                        // the producer set. Then sleep(250) inside the
                        // inner for-loop throws InterruptedException
                        // immediately, catch re-sets flag, continue --
                        // outer while restarts: chunked sleep ALSO
                        // throws immediately, infinite-spin at 100% CPU
                        // and zero new cars spawned forever.
                        // Thread.interrupted() returns AND clears the
                        // flag, breaking the spin. Once-per-iteration
                        // drain is enough -- the flag will never be
                        // set unless something interrupted us.
                        Thread.interrupted();

                        // [Concurrency Tech: Semaphore acquire as STOP gate]
                        // Centralised via carrefour.pauseIfStopped() so
                        // every worker (cars, lightManager, producer)
                        // uses the same gate logic.
                        carrefour.pauseIfStopped();

                        // [CONCURRENCY FIX - P2: chunked sleep for STOP responsiveness]
                        // Splitting circulationGrow (up to 8s) into 100ms
                        // chunks lets STOP take effect within ~100ms
                        // instead of up to 8s.
                        try {
                            int remaining = circulationGrow;
                            while (remaining > 0 && !stopButtonIsActive.get()) {
                                int chunk = Math.min(100, remaining);
                                sleep(chunk);
                                remaining -= chunk;
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            continue;
                        }
                        // [CONCURRENCY FIX - ADV-PROD-HANG-01]
                        // If STOP fired during the chunked sleep, do NOT
                        // fall through to the latch await. Loop back to
                        // pauseIfStopped() so the producer joins the
                        // semaphore queue properly.
                        if (stopButtonIsActive.get()) continue;

                        try {
                            if (settingChanged.get()) {
                                // [Concurrency Tech: CountDownLatch BOUNDED handoff]
                                // [CONCURRENCY FIX - simplified]
                                // The latch's purpose is purely cosmetic:
                                // it waits for the old generation of
                                // cars to drain off-screen before
                                // spawning the new generation with new
                                // settings. But correctness does NOT
                                // depend on it: each car captures its
                                // vitess as a final field at
                                // construction, so a slider change does
                                // not affect cars already on the road,
                                // and PHASE A's dynamic spacing keeps
                                // any mix of vitesses safely separated.
                                //
                                // Previously this was an unbounded
                                // await -- if any car was unable to
                                // exit (PHASE B stuck on a slow light
                                // cycle, STOP fired mid-await, etc.)
                                // the producer hung forever and no new
                                // cars ever appeared.
                                //
                                // We now wait at most 3 seconds for the
                                // latch to drain, then apply the new
                                // settings unconditionally and spawn.
                                // The old generation continues at its
                                // original speed; the new generation
                                // starts at the new speed; PHASE A
                                // mediates the lane spacing.
                                CountDownLatch latch = carCounterInTheStreet;
                                if (latch != null) {
                                    long deadline = System.currentTimeMillis() + 3000;
                                    while (latch.getCount() > 0
                                            && !stopButtonIsActive.get()
                                            && System.currentTimeMillis() < deadline) {
                                        if (latch.await(200, TimeUnit.MILLISECONDS)) break;
                                    }
                                }
                                if (!stopButtonIsActive.get()) {
                                    settingChanged.set(false);
                                }
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            continue;
                        }
                        // Same: if STOP fired during the latch wait, go
                        // back to the gate instead of spawning cars.
                        if (stopButtonIsActive.get()) continue;

                        // [CONCURRENCY FIX - P1#7 + ADV-LATCH-02: per-pair gate check]
                        // Break the burst on STOP *or* on a mid-burst slider
                        // change. Without the settingChanged check, a slider
                        // drag during the for-loop produced mixed-vitess cars
                        // in the same wave -- new fast cars and old slow cars
                        // could end up on the same lane simultaneously, which
                        // PHASE A throttles correctly but visually causes
                        // jitter. Breaking out lets the outer-loop's latch
                        // handshake drain old cars before next-wave spawn
                        // applies the new setting cleanly.
                        for (int i = 0; i < carsPerWave; i++) {
                            if (stopButtonIsActive.get() || settingChanged.get()) break;

                            int voie1Position = (new Random().nextInt(4)) + 1;
                            int voie2Position = (new Random().nextInt(4)) + 1;

                            // [CONCURRENCY FIX - EDT: panel install before worker start]
                            // createVoie*CarPanel performs the Swing
                            // mutations (setOpaque/setBounds/add) on the
                            // EDT via invokeAndWait so the panel is
                            // mounted before the worker thread starts.
                            imgVoitureVoie1 c1 = createVoie1CarPanel();
                            imgVoitureVoie2 c2 = createVoie2CarPanel();
                            voitureV1_V2 voitureVoie1 = new voitureV1_V2(carrefour, c1, voie1Position, voie1Position * speed);
                            voitureV2_V1 voitureVoie2 = new voitureV2_V1(carrefour, c2, voie2Position, voie2Position * speed);
                            voitureVoie1.start();
                            voitureVoie2.start();

                            try {
                                sleep(250);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            });
            createCars.start();
        }
    }

    public static void main(String[] args) {
        new MiniProjet_DAC().setVisible(true);

    }

}
