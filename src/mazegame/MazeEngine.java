package mazegame;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires together the Maze (data), the BaseExplorer (student algorithm,
 * running on its own thread) and the MazePanel (Swing rendering, on the EDT).
 *
 * Threading model: all mutable path / game-over state is only ever mutated
 * on the Swing Event Dispatch Thread. The explorer's solve() method runs on
 * a dedicated worker thread and calls into attemptMove(), which posts work
 * to the EDT and then blocks on a CountDownLatch until the animation + state
 * mutation is complete.
 *
 * Exploration state:
 * Two {@link ExplorationState} instances are kept: <i>logical</i> (worker)
 * and <i>visual</i> (EDT / render). The logical one drives algorithm queries
 * (hasVisited, visit eligibility, etc.). The visual one is advanced only
 * when an animation starts, so the camera and debug focus stay consistent
 * with what the player has actually seen. Frontier maintenance is incremental
 * (no full scan of visited cells on every move).
 */
public class MazeEngine {

    public interface GoalListener {
        void onGoalReached(int moveCount, int pathLength);
    }

    private final Maze maze;
    private final MazePanel panel;
    private final Deque<Cell> pathStack = new ArrayDeque<>();

    /**
     * Logical path / position owned by the solver thread.
     * Updated before animations are posted so canMove / getRow / etc. are
     * always consistent with the algorithm, even while the sprite lags.
     */
    private final List<Cell> logicPath = new ArrayList<>();
    private Cell logicCell;

    /** Logical state (worker thread) vs visual state (EDT / render thread). */
    private final ExplorationState logical;
    private final ExplorationState visual;

    private final AtomicInteger moveCount = new AtomicInteger(0);
    private volatile boolean gameOver = false;
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private volatile Thread solverThread;
    private GoalListener goalListener;

    public MazeEngine(Maze maze, MazePanel panel) {
        this.maze = maze;
        this.panel = panel;
        Cell start = maze.getStart();
        pathStack.push(start);
        logicPath.add(start);
        logicCell = start;
        this.logical = new ExplorationState(maze, start);
        this.visual = new ExplorationState(maze, start);
        panel.setEngineState(maze, pathStack, start);
        panel.setFrontier(new ArrayList<>(visual.getFrontier()));
    }

    public void setGoalListener(GoalListener listener) {
        this.goalListener = listener;
    }

    public void setAnimationDurationMs(int ms) {
        panel.setAnimationDurationMs(ms);
    }

    public void setCameraOmegaScale(double scale) {
        panel.setCameraOmegaScale(scale);
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isRunning() {
        Thread t = solverThread;
        return t != null && t.isAlive() && !gameOver;
    }

    public void stopCurrent() {
        gameOver = true;
        Thread t = solverThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public void resetToStart() {
        stopCurrent();
        try {
            if (solverThread != null) solverThread.join(200);
        } catch (InterruptedException ignored) {
        }

        Cell start = maze.getStart();
        pathStack.clear();
        pathStack.push(start);
        logicPath.clear();
        logicPath.add(start);
        logicCell = start;
        logical.reset(start);
        visual.reset(start);
        moveCount.set(0);
        gameOver = false;
        paused = false;
        panel.setEngineState(maze, pathStack, start);
        panel.setFrontier(new ArrayList<>(visual.getFrontier()));
        panel.repaint();
    }

    public void start(BaseExplorer explorer) {
        resetToStart();
        explorer.bind(this);
        solverThread = new Thread(() -> {
            try {
                explorer.solve();
            } catch (MazeStoppedException ignored) {
                // normal: the maze was reset / stopped while we were running
            } catch (Exception ex) {
                System.err.println("Explorer threw an exception:");
                ex.printStackTrace();
            }
        }, "MazeSolverThread");
        solverThread.setDaemon(true);
        solverThread.start();
    }

    private void waitIfPaused() {
        synchronized (pauseLock) {
            while (paused && !gameOver && !Thread.currentThread().isInterrupted()) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MazeStoppedException();
                }
            }
        }
        if (gameOver || Thread.currentThread().isInterrupted()) {
            throw new MazeStoppedException();
        }
    }

    boolean canMove(Direction dir) {
        Cell target = logicCell.moved(dir);
        // From the current (visited) cell, an orthogonal neighbor is visitable
        // iff it is open — same predicate as logical.canVisit.
        return logical.canVisit(target);
    }

    boolean hasVisited(Cell cell) {
        return logical.isVisited(cell);
    }

    /** True if {@link #attemptVisit} would succeed (no animation / no state change). */
    boolean canVisit(Cell cell) {
        return logical.canVisit(cell);
    }

    /** Called by BaseExplorer. Runs on the solver thread; blocks until the animation completes. */
    boolean attemptMove(Direction dir) {
        if (gameOver || Thread.currentThread().isInterrupted()) {
            throw new MazeStoppedException();
        }
        waitIfPaused();

        final Thread myThread = Thread.currentThread();
        moveCount.incrementAndGet();

        Cell current = logicCell;
        Cell target = current.moved(dir);

        if (!maze.isOpen(target)) {
            CountDownLatch latch = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                if (solverThread != myThread) {
                    latch.countDown();
                    return;
                }
                panel.animateBump(current, dir, latch);
            });
            await(latch);
            return false;
        }

        // Update logical path on the worker thread (mirrors Python _logic_path).
        boolean backingUp = logicPath.size() >= 2
                && logicPath.get(logicPath.size() - 2).equals(target);
        if (backingUp) {
            logicPath.remove(logicPath.size() - 1);
        } else {
            logicPath.add(target);
        }
        logicCell = target;

        // Only the first time we step onto a cell do we expand the logical
        // visited set / frontier. Re-visiting (backtracking) is a no-op.
        logical.visit(target);

        final List<Cell> pathSnapshot = List.copyOf(logicPath);

        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            if (solverThread != myThread) {
                latch.countDown();
                return;
            }
            // Rebuild visual path stack from the logical snapshot.
            pathStack.clear();
            for (int i = pathSnapshot.size() - 1; i >= 0; i--) {
                pathStack.push(pathSnapshot.get(i));
            }

            // Advance the *visual* exploration state in lock-step with the
            // animation. This keeps the camera / debug focus box consistent
            // with what the player has actually seen.
            visual.visit(target);
            panel.setFrontier(new ArrayList<>(visual.getFrontier()));
            panel.markExplored(target);
            panel.invalidateTrailCache();
            panel.animateMove(current, target, latch);
        });
        await(latch);

        if (target.equals(maze.getGoal())) {
            gameOver = true;
            int finalMoves = moveCount.get();
            int pathLen = logicPath.size();
            SwingUtilities.invokeLater(() -> {
                if (goalListener != null) goalListener.onGoalReached(finalMoves, pathLen);
            });
        }
        return true;
    }

    /**
     * Instantly move to a reachable cell: any already-visited cell, or an
     * open cell orthogonally adjacent to a visited cell.
     * Returns true on success (including a no-op when already there).
     * First visit onto a new cell marks it visited.
     * Does not increment the move counter.
     */
    boolean attemptVisit(Cell cell) {
        if (gameOver || Thread.currentThread().isInterrupted()) {
            throw new MazeStoppedException();
        }
        waitIfPaused();

        Cell current = logicCell;
        if (cell.equals(current)) {
            return true;
        }
        if (!logical.canVisit(cell)) {
            return false;
        }

        // Discovering a frontier neighbor via visit expands the logical visited
        // set the same way a normal step would.
        logical.visit(cell);

        // Reset logical path to a single cell (visit is a jump, not a step).
        logicPath.clear();
        logicPath.add(cell);
        logicCell = cell;

        final Thread myThread = Thread.currentThread();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            if (solverThread != myThread) {
                latch.countDown();
                return;
            }
            pathStack.clear();
            pathStack.push(cell);
            // Advance visual state; visit is a no-op when the cell was already known.
            visual.visit(cell);
            panel.setFrontier(new ArrayList<>(visual.getFrontier()));
            panel.markExplored(cell);
            panel.invalidateTrailCache();
            panel.animateHop(current, cell, latch);
        });
        await(latch);

        if (cell.equals(maze.getGoal())) {
            gameOver = true;
            int finalMoves = moveCount.get();
            int pathLen = logicPath.size();
            SwingUtilities.invokeLater(() -> {
                if (goalListener != null) goalListener.onGoalReached(finalMoves, pathLen);
            });
        }
        return true;
    }

    void markExplored(Cell cell) {
        panel.markExplored(cell);
    }

    void markExploredMany(List<Cell> cells) {
        panel.markExploredMany(cells);
    }

    void setShowSprite(boolean show) {
        panel.setShowSprite(show);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MazeStoppedException();
        }
        if (gameOver && !logicCell.equals(maze.getGoal())) {
            throw new MazeStoppedException();
        }
    }

    /**
     * Manhattan distance from {@code cell} (or the logical position if null)
     * to the goal.
     */
    double getHint(Cell cell) {
        Cell cur = cell != null ? cell : logicCell;
        Cell goal = maze.getGoal();
        return (double) (Math.abs(cur.row - goal.row) + Math.abs(cur.col - goal.col));
    }

    boolean isAtGoal() {
        return logicCell.equals(maze.getGoal());
    }

    int getRow() {
        return logicCell.row;
    }

    int getCol() {
        return logicCell.col;
    }

    int getMoveCount() {
        return moveCount.get();
    }
}
