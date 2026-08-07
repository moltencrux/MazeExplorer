package mazegame;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Random;

/**
 * A block-based maze. Every square in the grid is either OPEN or WALL.
 * The maze is generated with a recursive-backtracker (randomized DFS) carve,
 * which always produces a "perfect" maze: exactly one path between any two
 * open cells, and it is always fully solvable.
 *
 * The grid dimensions are always odd (2*cellsWide+1 by 2*cellsHigh+1) so that
 * every "logical" maze cell is separated from its neighbors by a wall square,
 * giving the classic block-maze look.
 */
public class Maze {

    private final boolean[][] wall; // wall[row][col] == true means blocked
    private final int rows;
    private final int cols;
    private final Cell start;
    private final Cell goal;

    /**
     * @param cellsWide number of logical (traversable) columns
     * @param cellsHigh number of logical (traversable) rows
     */
    public Maze(int cellsWide, int cellsHigh) {
        this(cellsWide, cellsHigh, new Random());
    }

    public Maze(int cellsWide, int cellsHigh, Random rand) {
        this.cols = cellsWide * 2 + 1;
        this.rows = cellsHigh * 2 + 1;
        this.wall = new boolean[rows][cols];
        for (boolean[] row : wall) {
            java.util.Arrays.fill(row, true);
        }
        carve(rand, cellsWide, cellsHigh);
        this.start = new Cell(1, 1);
        this.goal = new Cell(rows - 2, cols - 2);
    }

    private void carve(Random rand, int cellsWide, int cellsHigh) {
        boolean[][] visited = new boolean[cellsHigh][cellsWide];
        Deque<int[]> stack = new ArrayDeque<>();
        int startR = 0, startC = 0;
        visited[startR][startC] = true;
        wall[startR * 2 + 1][startC * 2 + 1] = false;
        stack.push(new int[]{startR, startC});

        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            java.util.List<int[]> candidates = new java.util.ArrayList<>();
            for (int[] d : dirs) {
                int nr = cur[0] + d[0];
                int nc = cur[1] + d[1];
                if (nr >= 0 && nr < cellsHigh && nc >= 0 && nc < cellsWide && !visited[nr][nc]) {
                    candidates.add(new int[]{nr, nc, d[0], d[1]});
                }
            }
            if (candidates.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] choice = candidates.get(rand.nextInt(candidates.size()));
            int nr = choice[0], nc = choice[1];
            // knock down the wall between cur and the chosen neighbor
            int wallRow = cur[0] * 2 + 1 + choice[2];
            int wallCol = cur[1] * 2 + 1 + choice[3];
            wall[wallRow][wallCol] = false;
            wall[nr * 2 + 1][nc * 2 + 1] = false;
            visited[nr][nc] = true;
            stack.push(new int[]{nr, nc});
        }
    }

    public boolean isOpen(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return false;
        return !wall[row][col];
    }

    public boolean isOpen(Cell c) {
        return isOpen(c.row, c.col);
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    public Cell getStart() {
        return start;
    }

    public Cell getGoal() {
        return goal;
    }
}
