package mazegame.explorers;

import mazegame.BaseExplorer;
import mazegame.Cell;
import mazegame.Direction;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Iterative depth-first search.
 *
 * Uses {@link #visit(Cell)} to jump to the cell being expanded. Neighbours
 * are discovered with {@link #canMove(Direction)} from that cell and pushed
 * onto a LIFO stack. Returns the reconstructed start→goal path from
 * {@link #solve()} so path length is correct (visit alone does not maintain
 * an engine trail).
 */
public class DFSExplorer extends BaseExplorer {

    private static final Direction[] DIRS = {
        Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT
    };

    @Override
    public List<Cell> solve() {
        Cell start = getCell();
        Set<Cell> visited = new HashSet<>();
        visited.add(start);
        Map<Cell, Cell> cameFrom = new HashMap<>();

        ArrayDeque<Cell> stack = new ArrayDeque<>();
        stack.push(start); // LIFO → DFS

        while (!stack.isEmpty()) {
            Cell cell = stack.pop();
            if (!visit(cell)) {
                continue;
            }
            visited.add(cell);

            if (isAtGoal()) {
                return reconstructPath(cameFrom, cell);
            }

            for (Direction direction : DIRS) {
                if (!canMove(direction)) {
                    continue;
                }
                Cell neighbour = cell.moved(direction);
                if (visited.contains(neighbour)) {
                    continue;
                }
                visited.add(neighbour);
                cameFrom.put(neighbour, cell);
                stack.push(neighbour);
            }
        }
        return reconstructPath(cameFrom, getCell());
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
