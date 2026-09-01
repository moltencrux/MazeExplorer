package mazegame;

import java.util.List;

/**
 * BaseExplorer is the class students subclass to implement a maze-solving
 * strategy (e.g. depth-first search, wall-following, A*, etc).
 *
 * How it works:
 *  - Override {@link #solve()}. This method is your algorithm's entry point.
 *    It runs on its own background thread, so you are free to use a loop,
 *    recursion, a Stack, a Queue -- whatever your algorithm needs -- without
 *    freezing the display.
 *  - Call {@link #moveUp()}, {@link #moveDown()}, {@link #moveLeft()}, or
 *    {@link #moveRight()} to attempt to move one square. Each call blocks
 *    until the move animation finishes and returns true if the move
 *    succeeded, or false if it was blocked by a wall (or the edge of the
 *    maze). A false return does NOT move you -- you're still on the same
 *    square.
 *  - Call {@link #canMoveUp()}, {@link #canMoveDown()}, {@link #canMoveLeft()},
 *    or {@link #canMoveRight()} to test whether a move would succeed without
 *    actually performing it (no animation, no path change, no move count).
 *  - Call {@link #teleport(Cell)} to jump instantly to a previously visited
 *    cell. Returns true if the cell has been visited, false otherwise.
 *  - Call {@link #hasVisited(Cell)} to check whether a cell is in the visited set.
 *  - Call {@link #isAtGoal()} to check if you've reached the goal.
 *  - Call {@link #getHint()} for a heuristic value (straight-line distance
 *    from your current square to the goal) that some algorithms -- like
 *    greedy best-first search or A* -- can use to decide which direction
 *    looks most promising. You are never required to use it.
 *  - Call {@link #getRow()} / {@link #getCol()} to see where you currently are.
 *  - Call {@link #markExplored(Cell)} to highlight a cell in the exploration
 *    overlay (optional; the engine already marks cells you step onto).
 *  - Call {@link #setShowSprite(boolean)} to hide the red agent dot (useful
 *    for frontier-style search where the highlight carries the visual).
 *
 * The camera automatically frames open leaves (cells that have been *visually*
 * revealed and still have an unvisited open neighbor). Students do not need to
 * manage that; the visual frontier lags the algorithm so it stays in sync with
 * the animation.
 *
 * You do NOT get direct access to the maze's wall layout. The only way to
 * find out what's around you is to try moving (or call the canMove* helpers)
 * and see whether it succeeds. That's the point of the exercise!
 * Teleport only works for cells you have already stepped onto via move_*.
 */
public abstract class BaseExplorer {

    private MazeEngine engine;

    /** Called internally by the engine -- do not call this yourself. */
    final void bind(MazeEngine engine) {
        this.engine = engine;
    }

    /**
     * Your maze-solving algorithm goes here. This runs on a background
     * thread, so blocking calls like moveUp() are safe to use in a loop.
     */
    public abstract void solve();

    protected final boolean move(Direction dir) {
        return engine.attemptMove(dir);
    }

    /** Attempts to move one square up. Returns true if successful. */
    protected final boolean moveUp() {
        return move(Direction.UP);
    }

    /** Attempts to move one square down. Returns true if successful. */
    protected final boolean moveDown() {
        return move(Direction.DOWN);
    }

    /** Attempts to move one square left. Returns true if successful. */
    protected final boolean moveLeft() {
        return move(Direction.LEFT);
    }

    /** Attempts to move one square right. Returns true if successful. */
    protected final boolean moveRight() {
        return move(Direction.RIGHT);
    }

    protected final boolean canMove(Direction dir) {
        return engine.canMove(dir);
    }

    /** Returns true if the explorer can move one square up without hitting a wall. Does not move. */
    protected final boolean canMoveUp() {
        return canMove(Direction.UP);
    }

    /** Returns true if the explorer can move one square down without hitting a wall. Does not move. */
    protected final boolean canMoveDown() {
        return canMove(Direction.DOWN);
    }

    /** Returns true if the explorer can move one square left without hitting a wall. Does not move. */
    protected final boolean canMoveLeft() {
        return canMove(Direction.LEFT);
    }

    /** Returns true if the explorer can move one square right without hitting a wall. Does not move. */
    protected final boolean canMoveRight() {
        return canMove(Direction.RIGHT);
    }

    /**
     * Jump to a previously visited cell.
     * Returns true if the teleport succeeded (cell was visited, or is the
     * current cell). Returns false if the cell has never been stepped on.
     */
    protected final boolean teleport(Cell cell) {
        return engine.attemptTeleport(cell);
    }

    /** True if the explorer has previously stepped onto this cell. */
    protected final boolean hasVisited(Cell cell) {
        return engine.hasVisited(cell);
    }

    /**
     * Returns a heuristic hint value for the current square: the straight-line
     * (Euclidean) distance to the goal. Smaller is closer. Not every maze
     * variant is guaranteed to provide a meaningful hint, but the default
     * maze always does.
     */
    protected final double getHint() {
        return engine.getHint(null);
    }

    /** True if the explorer is currently standing on the goal square. */
    protected final boolean isAtGoal() {
        return engine.isAtGoal();
    }

    /** The explorer's current row. */
    protected final int getRow() {
        return engine.getRow();
    }

    /** The explorer's current column. */
    protected final int getCol() {
        return engine.getCol();
    }

    protected final Cell getCell() {
        return new Cell(getRow(), getCol());
    }

    /** Total number of moves attempted so far (successful or not). */
    protected final int getMoveCount() {
        return engine.getMoveCount();
    }

    /**
     * Highlight a cell in the exploration overlay.
     * If cell is null, marks the explorer's current position.
     */
    protected final void markExplored(Cell cell) {
        if (cell == null) {
            cell = new Cell(engine.getRow(), engine.getCol());
        }
        engine.markExplored(cell);
    }

    /** Mark several cells at once. */
    protected final void markExploredMany(List<Cell> cells) {
        engine.markExploredMany(cells);
    }

    /**
     * Show or hide the red agent dot.
     * Frontier-style algorithms (A*, Dijkstra, ...) often hide it and rely
     * on the exploration highlight instead.
     */
    protected final void setShowSprite(boolean show) {
        engine.setShowSprite(show);
    }
}
