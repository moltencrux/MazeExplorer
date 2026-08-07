package mazegame;

/**
 * Thrown internally to unwind an Explorer's solve() thread when the maze is
 * reset or regenerated while that Explorer is still running. Students never
 * need to catch this themselves.
 */
public class MazeStoppedException extends RuntimeException {
    public MazeStoppedException() {
        super("The maze was reset while the explorer was still running.");
    }
}
