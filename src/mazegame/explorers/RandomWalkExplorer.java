package mazegame.explorers;

import mazegame.BaseExplorer;
import mazegame.Cell;
import mazegame.Direction;

import java.util.Random;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * The simplest possible (and usually worst-performing) strategy: at every
 * step, pick a random direction and try it. Tracks first-parent links so
 * {@link #solve()} can return a reconstructed start→goal path (not the full
 * walk trail with backtracking).
 */
public class RandomWalkExplorer extends BaseExplorer {

    private static final Direction[] DIRS = {
        Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT
    };

    private final Random rand = new Random();

    @Override
    public List<Cell> solve() {
        Map<Cell, Cell> cameFrom = new HashMap<>();

        while (!isAtGoal()) {
            Cell parent = getCell();
            Direction dir = DIRS[rand.nextInt(DIRS.length)];
            if (canMove(dir)) {
                move(dir);
                cameFrom.putIfAbsent(getCell(), parent);
            } else {
                // Attempt the bump so the engine can animate / count it.
                move(dir);
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
