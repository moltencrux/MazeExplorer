package mazegame;

/**
 * BaseExplorer is the class students subclass to implement a maze-solving
 * strategy (depth-first search, wall-following, A*, etc.).
 *
 * How it works:
 *  - Override {@link #solve()}. This method is the algorithm's entry point.
 *    It runs on its own background thread, so you are free to use a loop,
 *    recursion, a Stack, a Queue -- whatever your algorithm needs -- without
 *    freezing the display.
 *  - Call {@link #moveUp()}, {@link #moveDown()}, {@link #moveLeft()}, or
 *    {@link #moveRight()} to attempt to move one square. Each call blocks
 *    until the move animation finishes and returns true if the move
 *    succeeded, or false if it was blocked by a wall (or the edge of the
 *    maze). A false return does NOT move you.
 *  - Call {@link #canMoveUp()}, {@link #canMoveDown()}, {@link #canMoveLeft()},
 *    or {@link #canMoveRight()} to test whether a move would succeed without
 *    actually performing it (no animation, no path change, no move count).
 *  - Call {@link #visit(Cell)} to jump to a reachable cell: any previously
 *    visited cell, or an open cell orthogonally adjacent to a visited cell.
 *    First visit onto a new cell marks it visited. Returns true on success.
 *  - Call {@link #canVisit(Cell)} to test whether {@code visit(cell)} would
 *    succeed (no animation, no state change).
 *  - Call {@link #hasVisited(Cell)} to check whether a cell is in the visited set.
 *  - Call {@link #isAtGoal()} to check if you've reached the goal.
 *  - Call {@link #getHint()} / {@link #getHint(Cell)} for a heuristic value
 *    (Manhattan distance from your current square, or from an arbitrary cell,
 *    to the goal).
 *  - Call {@link #getRow()} / {@link #getCol()} to see where you currently are.
 *  - Call {@link #setShowSprite(boolean)} to hide the red agent dot (useful
 *    for frontier-style search where the highlight carries the visual).
 *
 * The camera automatically frames open leaves (cells that have been *visually*
 * revealed and still have an unvisited open neighbor). Students do not need to
 * manage that; the visual frontier lags the algorithm so it stays in sync with
 * the animation.
 *
 * You do NOT get direct access to the maze's wall layout. The only way to find
 * out what's around you is to try moving (or call the canMove* helpers).
 * {@code visit} works for visited cells and for open cells next to the visited set.
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
     * Jump to a reachable cell: any previously visited cell, or an open cell
     * orthogonally adjacent to a visited cell. First visit onto a new cell
     * marks it visited. Returns true on success (including a no-op when
     * already there).
     */
    protected final boolean visit(Cell cell) {
        return engine.attemptVisit(cell);
    }

    /**
     * True if {@link #visit(Cell)} would succeed (no animation / no state change).
     */
    protected final boolean canVisit(Cell cell) {
        return engine.canVisit(cell);
    }

    /** True if the explorer has previously stepped onto this cell. */
    protected final boolean hasVisited(Cell cell) {
        return engine.hasVisited(cell);
    }

    /**
     * Manhattan distance from the current square to the goal. Smaller is closer.
     */
    protected final double getHint() {
        return engine.getHint(null);
    }

    /**
     * Manhattan distance from {@code cell} to the goal. Smaller is closer.
     * Does not move the explorer.
     */
    protected final double getHint(Cell cell) {
        return engine.getHint(cell);
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
     * Show or hide the red agent dot.
     * Frontier-style algorithms (A*, Dijkstra, ...) often hide it and rely
     * on the exploration highlight instead.
     */
    protected final void setShowSprite(boolean show) {
        engine.setShowSprite(show);
    }
}
