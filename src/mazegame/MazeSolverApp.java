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
 * Explorer strategy, running it, and adjusting animation speed.
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
    private final JButton startButton = new JButton("Start Solving");
    private final JSlider speedSlider = new JSlider(1, 100, 60);
    private final JLabel statusLabel = new JLabel(" ");

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
        controls.add(newMazeButton);
        controls.add(new JLabel("Speed:"));
        speedSlider.setPreferredSize(new Dimension(120, speedSlider.getPreferredSize().height));
        controls.add(speedSlider);
        controls.add(statusLabel);

        newMazeButton.addActionListener(e -> generateNewMaze());
        startButton.addActionListener(e -> startSolving());
        speedSlider.addChangeListener(e -> applySpeed());

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(controls, BorderLayout.CENTER);
        return wrapper;
    }

    private void applySpeed() {
        // Slider is 1 (slowest) .. 100 (fastest). Map to an animation
        // duration in ms, higher slider value = shorter duration.
        int value = speedSlider.getValue();
        int durationMs = (int) (400 - (value / 100.0) * 380); // ranges ~ 20ms .. 400ms
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
        panel.revalidate();
        panel.repaint();
        SwingUtilities.invokeLater(this::pack);
    }

    private void startSolving() {
        String name = (String) explorerSelector.getSelectedItem();
        java.util.function.Supplier<BaseExplorer> factory = EXPLORERS.get(name);
        if (factory == null) return;
        BaseExplorer explorer = factory.get();
        statusLabel.setText("Solving with: " + name);
        engine.start(explorer);
    }

    private void onGoalReached(int moveCount, int pathLength) {
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
