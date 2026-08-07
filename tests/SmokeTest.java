package mazegame;

import mazegame.explorers.WallFollowerExplorer;

import javax.swing.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Not part of the student-facing app. A quick end-to-end smoke test that
 * exercises the real MazeEngine + MazePanel + BaseExplorer threading path
 * headlessly (run under Xvfb) to catch deadlocks/exceptions before shipping.
 */
public class SmokeTest {
    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] success = {false};
        int[] moves = {-1};

        SwingUtilities.invokeLater(() -> {
            Maze maze = new Maze(18, 14, new java.util.Random(42));
            MazePanel panel = new MazePanel();
            MazeEngine engine = new MazeEngine(maze, panel);
            engine.setAnimationDurationMs(2); // fast, for the test
            engine.setGoalListener((moveCount, pathLength) -> {
                success[0] = true;
                moves[0] = moveCount;
                done.countDown();
            });
            engine.start(new WallFollowerExplorer());
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        System.out.println("finished=" + finished + " success=" + success[0] + " moves=" + moves[0]);
        if (!finished || !success[0]) {
            System.exit(1);
        }

        // Now test interrupting a run mid-flight (regenerate while solving) doesn't hang/throw uncaught.
        CountDownLatch done2 = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            Maze maze2 = new Maze(18, 14, new java.util.Random(7));
            MazePanel panel2 = new MazePanel();
            MazeEngine engine2 = new MazeEngine(maze2, panel2);
            engine2.setAnimationDurationMs(2);
            engine2.start(new mazegame.explorers.RandomWalkExplorer());
            // let it run briefly, then reset mid-flight several times
            Timer t = new Timer(50, null);
            int[] count = {0};
            t.addActionListener(e -> {
                count[0]++;
                Maze m = new Maze(18, 14, new java.util.Random(count[0]));
                engine2.stopCurrent();
                MazeEngine fresh = new MazeEngine(m, panel2);
                fresh.setAnimationDurationMs(2);
                fresh.start(new mazegame.explorers.RandomWalkExplorer());
                if (count[0] >= 5) {
                    ((Timer) e.getSource()).stop();
                    done2.countDown();
                }
            });
            t.start();
        });
        boolean finished2 = done2.await(60, TimeUnit.SECONDS);
        System.out.println("stress-test finished=" + finished2);
        Thread.sleep(500); // let any straggler exceptions surface on stderr
        System.exit(finished2 ? 0 : 1);
    }
}
