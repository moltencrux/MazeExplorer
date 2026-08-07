package mazegame.explorers;

import mazegame.BaseExplorer;

import java.util.Random;

/**
 * The simplest possible (and usually worst-performing) strategy: at every
 * step, pick a random direction and try it. No memory, no strategy. Included
 * as a baseline students can compare their own algorithm against.
 */
public class RandomWalkExplorer extends BaseExplorer {

    private final Random rand = new Random();

    @Override
    public void solve() {
        while (!isAtGoal()) {
            switch (rand.nextInt(4)) {
                case 0 -> moveUp();
                case 1 -> moveDown();
                case 2 -> moveLeft();
                case 3 -> moveRight();
            }
        }
    }
}
