package mazegame;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the maze grid, the visited trail, and a smoothly-animated sprite
 * for the explorer's current position.
 */
public class MazePanel extends JPanel {

    private static final int ANIMATION_STEPS = 20;

    private Maze maze;
    private Deque<Cell> pathStack;
    private int cellSize = 22;

    // Sprite's current draw position, in pixels (panel-local, top-left of grid).
    private double spriteX, spriteY;
    private Cell spriteCell;

    // Used to draw a brief "bump" nudge when a move fails against a wall.
    private double bumpOffsetX = 0, bumpOffsetY = 0;

    public MazePanel() {
        setBackground(new Color(0xF5F5F5));
    }

    void setEngineState(Maze maze, Deque<Cell> pathStack, Cell startCell) {
        this.maze = maze;
        this.pathStack = pathStack;
        resetSprite(startCell);
        updatePreferredSize();
    }

    void resetSprite(Cell cell) {
        this.spriteCell = cell;
        Point2D p = cellCenter(cell);
        this.spriteX = p.x;
        this.spriteY = p.y;
        this.bumpOffsetX = 0;
        this.bumpOffsetY = 0;
        repaint();
    }

    private void updatePreferredSize() {
        int w = maze.getCols() * cellSize + 1;
        int h = maze.getRows() * cellSize + 1;
        setPreferredSize(new Dimension(w, h));
        revalidate();
    }

    private static class Point2D {
        double x, y;
        Point2D(double x, double y) { this.x = x; this.y = y; }
    }

    private Point2D cellCenter(Cell c) {
        return new Point2D(c.col * cellSize + cellSize / 2.0, c.row * cellSize + cellSize / 2.0);
    }

    /** Animate a smooth slide from one cell to an adjacent, open cell. */
    void animateMove(Cell from, Cell to, CountDownLatch latch) {
        Point2D fromPx = cellCenter(from);
        Point2D toPx = cellCenter(to);
        spriteCell = to;

        Timer[] timerHolder = new Timer[1];
        int[] step = {0};
        Timer timer = new Timer(Math.max(1, animationStepDelay()), null);
        timer.addActionListener(e -> {
            step[0]++;
            double t = Math.min(1.0, step[0] / (double) ANIMATION_STEPS);
            t = easeInOut(t);
            spriteX = fromPx.x + (toPx.x - fromPx.x) * t;
            spriteY = fromPx.y + (toPx.y - fromPx.y) * t;
            repaint();
            if (step[0] >= ANIMATION_STEPS) {
                timerHolder[0].stop();
                spriteX = toPx.x;
                spriteY = toPx.y;
                repaint();
                latch.countDown();
            }
        });
        timerHolder[0] = timer;
        timer.start();
    }

    /** Animate a small nudge toward a wall that blocked the move, then snap back. */
    void animateBump(Cell at, Direction dir, CountDownLatch latch) {
        Point2D center = cellCenter(at);
        double nudge = cellSize * 0.28;
        double targetX = center.x + dir.dCol * nudge;
        double targetY = center.y + dir.dRow * nudge;

        int totalSteps = 10; // out and back
        Timer[] timerHolder = new Timer[1];
        int[] step = {0};
        Timer timer = new Timer(Math.max(1, animationStepDelay() / 2), null);
        timer.addActionListener(e -> {
            step[0]++;
            double t = step[0] / (double) totalSteps;
            double phase = t <= 0.5 ? (t / 0.5) : (1 - (t - 0.5) / 0.5);
            spriteX = center.x + (targetX - center.x) * phase;
            spriteY = center.y + (targetY - center.y) * phase;
            repaint();
            if (step[0] >= totalSteps) {
                timerHolder[0].stop();
                spriteX = center.x;
                spriteY = center.y;
                repaint();
                latch.countDown();
            }
        });
        timerHolder[0] = timer;
        timer.start();
    }

    private volatile int externalAnimationDurationMs = 150;

    void setAnimationDurationMs(int ms) {
        this.externalAnimationDurationMs = Math.max(5, ms);
    }

    private int animationStepDelay() {
        return Math.max(1, externalAnimationDurationMs / ANIMATION_STEPS);
    }

    private static double easeInOut(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (maze == null) return;
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Grid
        for (int r = 0; r < maze.getRows(); r++) {
            for (int c = 0; c < maze.getCols(); c++) {
                boolean open = maze.isOpen(r, c);
                g.setColor(open ? Color.WHITE : new Color(0x2B2B3A));
                g.fillRect(c * cellSize, r * cellSize, cellSize, cellSize);
            }
        }

        // Trail: every cell in the current path except the one the sprite is on
        if (pathStack != null) {
            g.setColor(new Color(0x9FD8FF));
            int idx = 0;
            int size = pathStack.size();
            for (Cell c : pathStack) {
                if (idx > 0) { // skip the head (current sprite cell)
                    int pad = 4;
                    g.fillRoundRect(c.col * cellSize + pad, c.row * cellSize + pad,
                            cellSize - 2 * pad, cellSize - 2 * pad, 6, 6);
                }
                idx++;
            }
        }

        // Start & goal markers
        drawMarker(g, maze.getStart(), new Color(0x4CAF50), "S");
        drawMarker(g, maze.getGoal(), new Color(0xFFB300), "G");

        // Grid lines (subtle)
        g.setColor(new Color(0, 0, 0, 25));
        for (int r = 0; r <= maze.getRows(); r++) {
            g.drawLine(0, r * cellSize, maze.getCols() * cellSize, r * cellSize);
        }
        for (int c = 0; c <= maze.getCols(); c++) {
            g.drawLine(c * cellSize, 0, c * cellSize, maze.getRows() * cellSize);
        }

        // Sprite
        double radius = cellSize * 0.32;
        g.setColor(new Color(0xE53935));
        Ellipse2D dot = new Ellipse2D.Double(spriteX - radius, spriteY - radius, radius * 2, radius * 2);
        g.fill(dot);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.draw(dot);
    }

    private void drawMarker(Graphics2D g, Cell c, Color color, String label) {
        int pad = 3;
        g.setColor(color);
        g.fillRoundRect(c.col * cellSize + pad, c.row * cellSize + pad,
                cellSize - 2 * pad, cellSize - 2 * pad, 6, 6);
        g.setColor(Color.WHITE);
        g.setFont(getFont().deriveFont(Font.BOLD, cellSize * 0.5f));
        FontMetrics fm = g.getFontMetrics();
        int tx = c.col * cellSize + (cellSize - fm.stringWidth(label)) / 2;
        int ty = c.row * cellSize + (cellSize + fm.getAscent()) / 2 - 2;
        g.drawString(label, tx, ty);
    }
}
