package mazegame.explorers;

import mazegame.BaseExplorer;
import mazegame.Direction;

/**
 * The classic "right-hand rule": keep your right hand on the wall and follow
 * it. This is guaranteed to solve any "perfect" maze (one with no loops --
 * which is exactly what this game generates) because it's equivalent to a
 * depth-first traversal of the maze's spanning tree. It's rarely the
 * shortest path, but it always finds a way through.
 *
 * Included as a second working example so students can see a real (if
 * naive) strategy alongside RandomWalkExplorer.
 */
public class WallFollowerExplorer extends BaseExplorer {

    @Override
    public void solve() {
        final Direction dirs[] = { Direction.RIGHT, Direction.UP,
                Direction.LEFT, Direction.DOWN };

        int turn = 0;
        while (!isAtGoal()) {
            
            while (!canMove(dirs[turn])) {
                turn = (turn + 1) % 4;
            }

            move(dirs[turn]);
            turn = (turn + 3) % 4;
        }
    }
}
