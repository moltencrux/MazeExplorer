package mazegame;

import javax.swing.SwingUtilities;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires together the Maze (data), the BaseExplorer (student algorithm,
 * running on its own thread) and the MazePanel (Swing rendering, on the EDT).
 *
 * Threading model: all mutable state (the current path stack, the
 * game-over flag) is only ever mutated on the Swing Event Dispatch Thread.
 * The explorer's solve() method runs on a dedicated worker thread and calls
 * into attemptMove(), which posts work to the EDT and then blocks on a
 * CountDownLatch until the animation + state mutation is complete. The
 * latch's await()/countDown() pair guarantees the worker thread sees the
 * EDT's writes once it wakes up, so no extra synchronization is needed.
 */
public class MazeEngine {

    public interface GoalListener {
        void onGoalReached(int moveCount, int pathLength);
    }

    private final Maze maze;
    private final MazePanel panel;
    private final Deque<Cell> pathStack = new ArrayDeque<>();
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
        panel.setEngineState(maze, pathStack, maze.getStart());
    }

    public void setGoalListener(GoalListener listener) {
        this.goalListener = listener;
    }

    public void setAnimationDurationMs(int ms) {
        panel.setAnimationDurationMs(ms);
    }

    /** Pause the current solve. No-op if nothing is running. */
    public void pause() {
        paused = true;
    }

    /** Resume a paused solve. */
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

    /**
     * Stops any currently running explorer thread (if any) by interrupting it,
     * so a new run can start cleanly.
     */
    public void stopCurrent() {
        gameOver = true;
        Thread t = solverThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /** Starts a fresh run of the given explorer instance on a background thread. */
    public void start(BaseExplorer explorer) {
        stopCurrent();
        try {
            if (solverThread != null) solverThread.join(200);
        } catch (InterruptedException ignored) {
        }

        pathStack.clear();
        pathStack.push(maze.getStart());
        moveCount.set(0);
        gameOver = false;
        paused = false;
        panel.resetSprite(maze.getStart());
        panel.repaint();

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

    /**
     * Returns true if a move in the given direction would succeed, without
     * performing it. Safe to call from the solver thread; has no side effects
     * (no animation, no path change, no move count increment).
     */
    boolean canMove(Direction dir) {
        Cell current = pathStack.peek();
        Cell target = current.moved(dir);
        return maze.isOpen(target);
    }

    /** Called by BaseExplorer. Runs on the solver thread; blocks until the animation completes. */
    boolean attemptMove(Direction dir) {
        if (gameOver || Thread.currentThread().isInterrupted()) {
            throw new MazeStoppedException();
        }
        waitIfPaused();

        // Tag this call with the thread that made it. If the maze gets reset
        // (a new solverThread installed) while our EDT callback is still
        // queued, the callback below detects the mismatch and no-ops instead
        // of mutating state that no longer belongs to this run.
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

        // "Backing up" = moving onto the cell immediately behind us in the
        // current path. That cell's trail mark disappears once we leave it.
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
            // Let the algorithm's solve() return naturally; further moves are blocked.
        }
        return true;
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
            // Game was reset out from under us mid-animation.
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
