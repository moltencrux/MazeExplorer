package mazegame;

import mazegame.explorers.RandomWalkExplorer;
import mazegame.explorers.WallFollowerExplorer;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The runnable entry point. Builds the window, wires up the maze / engine /
 * panel, and provides simple controls for generating a new maze, picking an
 * Explorer strategy, running / pausing / resetting it, and adjusting animation speed.
 *
 * ------------------------------------------------------------------------
 * STUDENTS: to add your own strategy, subclass BaseExplorer (see
 * mazegame.explorers.RandomWalkExplorer for the simplest possible example),
 * then add one line to the EXPLORERS map below:
 *
 *     EXPLORERS.put("My Algorithm", MyExplorer::new);
 * ------------------------------------------------------------------------
 */
public class MazeSolverApp extends JFrame {

    // name -> factory (a Supplier so a fresh instance is created every run)
    private static final Map<String, java.util.function.Supplier<BaseExplorer>> EXPLORERS = new LinkedHashMap<>();
    static {
        EXPLORERS.put("Random Walk (example)", RandomWalkExplorer::new);
        EXPLORERS.put("Wall Follower (example)", WallFollowerExplorer::new);
        // Add your own strategy here, e.g.:
        // EXPLORERS.put("My DFS Explorer", MyDfsExplorer::new);
    }

    private static final int DEFAULT_CELLS_WIDE = 18;
    private static final int DEFAULT_CELLS_HIGH = 14;

    private final MazePanel panel = new MazePanel();
    private Maze maze;
    private MazeEngine engine;

    private final JComboBox<String> explorerSelector = new JComboBox<>(EXPLORERS.keySet().toArray(new String[0]));
    private final JButton newMazeButton = new JButton("New Maze");
    private final JButton startButton = createPlayPauseButton();
    private final JButton resetButton = createResetButton();
    private final JSlider speedSlider = new JSlider(1, 100, 60);
    private final JLabel statusLabel = new JLabel(" ");

    private enum SolveState { IDLE, RUNNING, PAUSED }

    public MazeSolverApp() {
        super("Maze Explorer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JScrollPane scrollPane = new JScrollPane(panel);
        add(scrollPane, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        generateNewMaze();

        pack();
        setLocationRelativeTo(null);
    }

    private JComponent buildControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        controls.add(new JLabel("Explorer:"));
        controls.add(explorerSelector);
        controls.add(startButton);
        controls.add(resetButton);
        controls.add(newMazeButton);
        controls.add(new JLabel("Speed:"));
        speedSlider.setPreferredSize(new Dimension(120, speedSlider.getPreferredSize().height));
        controls.add(speedSlider);
        controls.add(statusLabel);

        newMazeButton.addActionListener(e -> generateNewMaze());
        startButton.addActionListener(e -> onPlayPause());
        resetButton.addActionListener(e -> resetSolving());
        speedSlider.addChangeListener(e -> applySpeed());

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(controls, BorderLayout.CENTER);
        return wrapper;
    }

    // ------------------------------------------------------------------
    // Custom buttons
    // ------------------------------------------------------------------

    private static JButton createPlayPauseButton() {
        JButton btn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);

                String mode = (String) getClientProperty("mode");
                boolean isPause = "pause".equals(mode);

                int w = getWidth(), h = getHeight();
                int size = Math.min(w, h) - 6;
                int x = (w - size) / 2;
                int y = (h - size) / 2;

                g2.setColor(isPause ? new Color(40, 40, 40) : new Color(0, 160, 0));

                if (isPause) {
                    // || bars
                    int barW = size / 4;
                    int gap  = size / 6;
                    int total = barW * 2 + gap;
                    int left = x + (size - total) / 2;
                    g2.fillRoundRect(left, y, barW, size, 2, 2);
                    g2.fillRoundRect(left + barW + gap, y, barW, size, 2, 2);
                } else {
                    // ▶ triangle
                    int[] xs = { x, x, x + size };
                    int[] ys = { y, y + size, y + size / 2 };
                    g2.fillPolygon(xs, ys, 3);
                }
                g2.dispose();
            }

            @Override public Dimension getPreferredSize() { return new Dimension(28, 28); }
            @Override public Dimension getMinimumSize()   { return getPreferredSize(); }
            @Override public Dimension getMaximumSize()   { return getPreferredSize(); }
        };

        btn.putClientProperty("mode", "play");
        btn.setToolTipText("Start / Pause");
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        return btn;
    }

    private static JButton createResetButton() {
        JButton btn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);

                int size = Math.min(getWidth(), getHeight()) - 3;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;

                // light-blue filled circle
                Color blue = new Color(70, 140, 230);
                g2.setColor(blue);
                g2.fillOval(x, y, size, size);
                g2.setColor(blue.darker());
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawOval(x, y, size, size);

                // white circular-arrow (refresh) icon
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                int pad = Math.max(3, size / 5);
                int arc = size - 2 * pad;

                // open arc (clockwise-ish)



                // draw two arcs with arrowheads
                for (int theta_deg : new int[] { 40, 220 }) {

                    g2.drawArc(x + pad, y + pad, arc, arc, theta_deg - 120, 120);

                    // ---- arrow-head (user geometry) ----
                    double theta = Math.toRadians(theta_deg); // same angle used for the arc start
                    int cx = x + size / 2;
                    int cy = y + size / 2;
                    int r = arc / 2; // radius of the arc
                    int b = Math.max(4, size * 2 / 5); // base width of the arrowhead

                    // local coordinates in the θ = 0 frame
                    double[][] local = {
                            { r, b / 2.0 }, // tip
                            { r - b / 2.0, 0 }, // inner barb
                            { r + b / 2.0, 0 } // outer barb
                    };

                    double cos = Math.cos(theta);
                    double sin = Math.sin(theta);

                    int[] xs = new int[3];
                    int[] ys = new int[3];
                    for (int i = 0; i < 3; i++) {
                        double lx = local[i][0];
                        double ly = local[i][1];
                        // rotate
                        double rx = lx * cos - ly * sin;
                        double ry = lx * sin + ly * cos;
                        // translate to circle centre + flip y for screen coordinates
                        xs[i] = cx + (int) Math.round(rx);
                        ys[i] = cy - (int) Math.round(ry);
                    }

                    g2.fillPolygon(xs, ys, 3);

                }

                g2.dispose();
            }

            @Override public Dimension getPreferredSize() { return new Dimension(28, 28); }
            @Override public Dimension getMinimumSize()   { return getPreferredSize(); }
            @Override public Dimension getMaximumSize()   { return getPreferredSize(); }
        };

        btn.setToolTipText("Reset");
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setBackground(new Color(70, 140, 230));
        return btn;
    }

    // ------------------------------------------------------------------
    // State management
    // ------------------------------------------------------------------

    private void setSolveState(SolveState state) {
        switch (state) {
            case IDLE -> {
                startButton.setEnabled(true);
                startButton.putClientProperty("mode", "play");
                explorerSelector.setEnabled(true);
                resetButton.setEnabled(false);
            }
            case RUNNING -> {
                startButton.setEnabled(true);
                startButton.putClientProperty("mode", "pause");
                explorerSelector.setEnabled(false);
                resetButton.setEnabled(true);
            }
            case PAUSED -> {
                startButton.setEnabled(true);
                startButton.putClientProperty("mode", "play");
                explorerSelector.setEnabled(false);
                resetButton.setEnabled(true);
            }
        }
        startButton.repaint();
        resetButton.repaint();
    }

    private void applySpeed() {
        // Slider is 1 (slowest) .. 100 (fastest). Map to an animation
        // duration in ms, higher slider value = shorter duration.
        int value = speedSlider.getValue();
        int durationMs = (int) Math.round(100 * ((100 - value) / 100.0)); // ~600ms .. 0ms
        if (engine != null) {
            engine.setAnimationDurationMs(durationMs);
        }
    }

    private void generateNewMaze() {
        if (engine != null) {
            engine.stopCurrent();
        }
        maze = new Maze(DEFAULT_CELLS_WIDE, DEFAULT_CELLS_HIGH);
        engine = new MazeEngine(maze, panel);
        engine.setGoalListener(this::onGoalReached);
        applySpeed();
        statusLabel.setText(" ");
        setSolveState(SolveState.IDLE);
        panel.revalidate();
        panel.repaint();
        SwingUtilities.invokeLater(this::pack);
    }

    private void onPlayPause() {
        if (engine == null) return;

        if (!engine.isRunning()) {
            // idle → start a new solve
            startSolving();
        } else if (engine.isPaused()) {
            engine.resume();
            setSolveState(SolveState.RUNNING);
            String current = statusLabel.getText();
            if (current.startsWith("Paused")) {
                statusLabel.setText(current.replace("Paused.", "Solving…"));
            }
        } else {
            engine.pause();
            setSolveState(SolveState.PAUSED);
            statusLabel.setText("Paused.");
        }
    }

    private void startSolving() {
        String name = (String) explorerSelector.getSelectedItem();
        java.util.function.Supplier<BaseExplorer> factory = EXPLORERS.get(name);
        if (factory == null) return;
        BaseExplorer explorer = factory.get();
        statusLabel.setText("Solving with: " + name);
        setSolveState(SolveState.RUNNING);
        engine.start(explorer);
    }

    /** Restart from the beginning of the current maze.
     *  - If currently running  → reset + immediately start solving again.
     *  - If currently paused   → reset only (stay idle so the user can press ▶).
     */
    private void resetSolving() {
	if (engine == null) return;

	boolean wasRunning = engine.isRunning() && !engine.isPaused();

	if (wasRunning) {
	    // reset + auto-restart
	    String name = (String) explorerSelector.getSelectedItem();
	    java.util.function.Supplier<BaseExplorer> factory = EXPLORERS.get(name);
	    if (factory == null) return;
	    BaseExplorer explorer = factory.get();
	    statusLabel.setText("Solving with: " + name);
	    setSolveState(SolveState.RUNNING);
	    engine.start(explorer);
	} else {
	    // paused (or any non-running case) → just rewind to start
	    engine.resetToStart();
	    statusLabel.setText("Reset to start.");
	    setSolveState(SolveState.IDLE);
	}
    }

    private void onGoalReached(int moveCount, int pathLength) {
        setSolveState(SolveState.IDLE);
        statusLabel.setText("Solved! " + moveCount + " moves attempted, final path length " + pathLength + ".");
        String message = String.format(
                "You reached the goal!%n%nMoves attempted: %d%nFinal path length: %d squares%n%nPlay again with a new maze?",
                moveCount, pathLength);
        int choice = JOptionPane.showConfirmDialog(this, message, "Maze Solved!",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            generateNewMaze();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MazeSolverApp app = new MazeSolverApp();
            app.setVisible(true);
        });
    }
}
