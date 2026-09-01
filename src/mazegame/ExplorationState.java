package mazegame;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks the set of visited cells and the open frontier (visited cells that
 * still have at least one open, unvisited neighbor).
 *
 * Two instances are used in {@link MazeEngine}:
 * <ul>
 *   <li><b>logical</b> — worker / algorithm thread (drives hasVisited, teleport eligibility)</li>
 *   <li><b>visual</b> — EDT / render thread — advanced only when an animation starts,
 *       so the camera and focus stay consistent with what the player has actually seen</li>
 * </ul>
 */
public final class ExplorationState {

    private final Maze maze;
    private final Set<Cell> visited = new HashSet<>();
    private final Set<Cell> frontier = new HashSet<>();

    public ExplorationState(Maze maze, Cell start) {
        this.maze = maze;
        visited.add(start);
        if (hasOpenUnvisitedNeighbor(start)) {
            frontier.add(start);
        }
    }

    public boolean isVisited(Cell cell) {
        return visited.contains(cell);
    }

    /** Unmodifiable view of the current frontier. */
    public Set<Cell> getFrontier() {
        return Collections.unmodifiableSet(frontier);
    }

    /** True if the cell is open and has not yet been visited. */
    public boolean canVisit(Cell cell) {
        return maze.isOpen(cell) && !visited.contains(cell);
    }

    /**
     * Mark {@code cell} visited and maintain the frontier incrementally.
     * Caller is responsible for ensuring this is a legal discovery
     * (e.g. an adjacent open cell reached by a normal move).
     * Re-visiting is a no-op.
     */
    public void visit(Cell cell) {
        if (visited.contains(cell)) {
            return;
        }

        visited.add(cell);

        if (hasOpenUnvisitedNeighbor(cell)) {
            frontier.add(cell);
        }

        // Neighbors that previously had this cell as their only remaining
        // unvisited open neighbor may now be dead-ends.
        for (Direction d : Direction.values()) {
            Cell neighbor = cell.moved(d);
            if (visited.contains(neighbor) && !hasOpenUnvisitedNeighbor(neighbor)) {
                frontier.remove(neighbor);
            }
        }
    }

    private boolean hasOpenUnvisitedNeighbor(Cell cell) {
        for (Direction d : Direction.values()) {
            Cell n = cell.moved(d);
            if (maze.isOpen(n) && !visited.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Reset to a fresh state with only {@code start} visited. */
    public void reset(Cell start) {
        visited.clear();
        frontier.clear();
        visited.add(start);
        if (hasOpenUnvisitedNeighbor(start)) {
            frontier.add(start);
        }
    }
}
