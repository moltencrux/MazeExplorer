package mazegame;

/**
 * The four cardinal directions an Explorer can move.
 * Row increases downward, column increases rightward (standard screen/grid convention).
 */
public enum Direction {
    UP(-1, 0),
    DOWN(1, 0),
    LEFT(0, -1),
    RIGHT(0, 1);

    public final int dRow;
    public final int dCol;

    Direction(int dRow, int dCol) {
        this.dRow = dRow;
        this.dCol = dCol;
    }
}
