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
 * Additionally tracks a visited set and open frontier so the soft-follow
 * camera can frame the active search front, and supports markExplored /
 * teleport for advanced explorers.
 */
public class MazeEngine {

    public interface GoalListener {
        void onGoalReached(int moveCount, int pathLength);
    }

    private final Maze maze;
    private final MazePanel panel;
    private final Deque<Cell> pathStack = new ArrayDeque<>();
    /** Every cell the explorer has successfully stepped onto (or started on). */
    private final Set<Cell> visited = new HashSet<>();
    /** Visited cells that still have at least one unvisited open neighbor. */
    private final Set<Cell> visitedOpen = new HashSet<>();
    private final AtomicInteger moveCount = new AtomicInteger(0);
    private volatile boolean gameOver = false;
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private volatile Thread solverThread;
    private GoalListener goalListener;

    public MazeEngine(Maze maze, MazePanel panel) {
        this.maze = maze;
        this.panel = panel;
        pathStack.push(maze.getStart());
        visited.add(maze.getStart());
        visitedOpen.add(maze.getStart());
        panel.setEngineState(maze, pathStack, maze.getStart());
        recomputeFrontier();
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

        pathStack.clear();
        pathStack.push(maze.getStart());
        visited.clear();
        visited.add(maze.getStart());
        visitedOpen.clear();
        visitedOpen.add(maze.getStart());
        moveCount.set(0);
        gameOver = false;
        paused = false;
        panel.setEngineState(maze, pathStack, maze.getStart());
        recomputeFrontier();
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
        Cell current = pathStack.peek();
        Cell target = current.moved(dir);
        return maze.isOpen(target);
    }

    boolean hasVisited(Cell cell) {
        return visited.contains(cell);
    }

    /**
     * Open leaves = visited cells that still have an unvisited open neighbor.
     * Derived from the maze + visited set so explorers never manage a frontier.
     */
    private void recomputeFrontier() {
        List<Cell> frontier = new ArrayList<>();
        Set<Cell> deadEnds = new HashSet<>();
        for (Cell cell : visitedOpen) {
            boolean hasOpenNeighbor = false;
            for (Direction d : Direction.values()) {
                Cell neighbor = cell.moved(d);
                if (maze.isOpen(neighbor) && !visited.contains(neighbor)) {
                    hasOpenNeighbor = true;
                    break;
                }
            }
            if (hasOpenNeighbor) {
                frontier.add(cell);
            } else {
                deadEnds.add(cell);
            }
        }
        visitedOpen.removeAll(deadEnds);
        panel.setFrontier(frontier);
    }

    /** Called by BaseExplorer. Runs on the solver thread; blocks until the animation completes. */
    boolean attemptMove(Direction dir) {
        if (gameOver || Thread.currentThread().isInterrupted()) {
            throw new MazeStoppedException();
        }
        waitIfPaused();

        final Thread myThread = Thread.currentThread();
        moveCount.incrementAndGet();

        Cell current = pathStack.peek();
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

        Cell previous = secondFromTop();
        boolean backingUp = previous != null && previous.equals(target);

        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            if (solverThread != myThread) {
                latch.countDown();
                return;
            }
            if (backingUp) {
                pathStack.pop();
            } else {
                pathStack.push(target);
            }
            visited.add(target);
            visitedOpen.add(target);
            recomputeFrontier();
            panel.markExplored(target);
            panel.invalidateTrailCache();
            panel.animateMove(current, target, latch);
        });
        await(latch);

        if (target.equals(maze.getGoal())) {
            gameOver = true;
            int finalMoves = moveCount.get();
            int pathLen = pathStack.size();
            SwingUtilities.invokeLater(() -> {
                if (goalListener != null) goalListener.onGoalReached(finalMoves, pathLen);
            });
        }
        return true;
    }

    /**
     * Instantly move the sprite to a previously visited cell.
     * Returns true on success, false if the cell has never been visited
     * (or is the current cell - treated as a no-op success).
     */
    boolean attemptTeleport(Cell cell) {
        if (gameOver || Thread.currentThread().isInterrupted()) {
            throw new MazeStoppedException();
        }
        waitIfPaused();

        Cell current = pathStack.peek();
        if (cell.equals(current)) return true;
        if (!visited.contains(cell)) return false;

        final Thread myThread = Thread.currentThread();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            if (solverThread != myThread) {
                latch.countDown();
                return;
            }
            pathStack.clear();
            pathStack.push(cell);
            panel.invalidateTrailCache();
            panel.animateHop(current, cell, latch);
        });
        await(latch);

        if (cell.equals(maze.getGoal())) {
            gameOver = true;
            int finalMoves = moveCount.get();
            int pathLen = pathStack.size();
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

    private Cell secondFromTop() {
        int i = 0;
        for (Cell c : pathStack) {
            if (i == 1) return c;
            i++;
        }
        return null;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MazeStoppedException();
        }
        if (gameOver && !pathStack.peek().equals(maze.getGoal())) {
            throw new MazeStoppedException();
        }
    }

    double getHint() {
        Cell cur = pathStack.peek();
        Cell goal = maze.getGoal();
        int dr = cur.row - goal.row;
        int dc = cur.col - goal.col;
        return Math.sqrt(dr * dr + dc * dc);
    }

    boolean isAtGoal() {
        return pathStack.peek().equals(maze.getGoal());
    }

    int getRow() {
        return pathStack.peek().row;
    }

    int getCol() {
        return pathStack.peek().col;
    }

    int getMoveCount() {
        return moveCount.get();
    }
}
