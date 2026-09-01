package mazegame.explorers;

import mazegame.BaseExplorer;
import mazegame.Cell;
import mazegame.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* search explorer.
 *
 * Uses {@link #visit(Cell)} to jump to the node being expanded. neighbors
 * are only probed with {@link #canMove(Direction)} / {@link #getHint(Cell)} —
 * they are claimed (marked visited) when later expanded from the open set,
 * so the sprite does not thrash back and forth on every edge relaxation.
 *
 * Returns the reconstructed start→goal path from {@link #solve()} so path
 * length is correct (visit alone does not maintain an engine trail).
 */
public class AStarExplorer extends BaseExplorer {

    private static final Direction[] DIRS = {
        Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT
    };

    /** Heap entry: (f, g, tie, cell). Lower f first; then lower g; then insertion order. */
    private static final class Node implements Comparable<Node> {
        final double f;
        final double g;
        final int tie;
        final Cell cell;

        Node(double f, double g, int tie, Cell cell) {
            this.f = f;
            this.g = g;
            this.tie = tie;
            this.cell = cell;
        }

        @Override
        public int compareTo(Node o) {
            int c = Double.compare(this.f, o.f);
            if (c != 0) return c;
            c = Double.compare(this.g, o.g);
            if (c != 0) return c;
            return Integer.compare(this.tie, o.tie);
        }
    }

    @Override
    public List<Cell> solve() {
        // Rely on the exploration highlight instead of the hopping red dot.
        setShowSprite(false);

        Cell start = getCell();

        int counter = 0;
        PriorityQueue<Node> openHeap = new PriorityQueue<>();
        openHeap.add(new Node(getHint(start), 0.0, counter, start));

        Map<Cell, Double> gScore = new HashMap<>();
        gScore.put(start, 0.0);

        Map<Cell, Cell> cameFrom = new HashMap<>();
        Set<Cell> closed = new HashSet<>();

        while (!openHeap.isEmpty()) {
            Node best = openHeap.poll();
            Cell cell = best.cell;
            double g = best.g;

            // Stale entry: a better path to this cell was found after enqueue.
            Double knownG = gScore.get(cell);
            if (knownG != null && g > knownG) {
                continue;
            }
            if (closed.contains(cell)) {
                continue;
            }
            closed.add(cell);

            // Jump to this node so it is marked visited (and the overlay updates).
            if (!visit(cell)) {
                continue;
            }

            if (isAtGoal()) {
                return reconstructPath(cameFrom, cell);
            }

            for (Direction direction : DIRS) {
                // Must be standing on `cell` to probe; visit back if needed.
                if (!getCell().equals(cell)) {
                    if (!visit(cell)) {
                        break;
                    }
                }

                if (!canMove(direction)) {
                    continue;
                }

                Cell neighbor = cell.moved(direction);
                if (closed.contains(neighbor)) {
                    continue;
                }

                double tentativeG = g + 1.0;
                Double existingG = gScore.get(neighbor);
                if (existingG != null && tentativeG >= existingG) {
                    continue;
                }

                cameFrom.put(neighbor, cell);
                gScore.put(neighbor, tentativeG);
                double fN = tentativeG + getHint(neighbor);
                counter++;
                openHeap.add(new Node(fN, tentativeG, counter, neighbor));
            }
        }
        return null;
    }

    private static List<Cell> reconstructPath(Map<Cell, Cell> cameFrom, Cell goal) {
        List<Cell> path = new ArrayList<>();
        path.add(goal);
        Cell cur = goal;
        while (cameFrom.containsKey(cur)) {
            cur = cameFrom.get(cur);
            path.add(cur);
        }
        Collections.reverse(path);
        return path;
    }
}
