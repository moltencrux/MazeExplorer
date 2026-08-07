# Maze Explorer

A Java Swing maze-solving game/teaching tool. A random block-based maze is
generated and shown on screen. Students implement a maze-solving algorithm
by subclassing `BaseExplorer`; the app animates their agent moving through
the maze, leaves a trail behind it (which un-marks itself when the agent
backtracks), and congratulates the player with a "play again?" prompt when
the goal is reached.

## Requirements

- JDK 17 or newer (developed/tested on JDK 21). No external libraries.

## Build & run

From the project root:

```bash
mkdir -p out
javac -d out $(find src -name "*.java")
java -cp out mazegame.MazeSolverApp
```

Or open the `src` folder as a plain Java project in any IDE (IntelliJ,
Eclipse, VS Code) and run `mazegame.MazeSolverApp`.

## How it works

| File | Purpose |
|---|---|
| `Maze.java` | Generates a random "perfect" maze (recursive backtracker) and answers `isOpen(row, col)` queries. Always fully solvable. |
| `Direction.java`, `Cell.java` | Small value types. |
| `BaseExplorer.java` | **The class students subclass.** Exposes `moveUp/Down/Left/Right()`, `canMoveUp/Down/Left/Right()`, `getHint()`, `isAtGoal()`, `getRow()/getCol()`. No direct access to the maze layout — the only way to learn about your surroundings is to try moving (or call the canMove* helpers). |
| `MazeEngine.java` | Glue between the maze, the explorer, and the panel. Runs the student's `solve()` on a background thread; each move call blocks until its animation finishes, so algorithms can be written as simple, synchronous loops. |
| `MazePanel.java` | Swing rendering: grid, trail, start/goal markers, and the smoothly-animated sprite. |
| `MazeSolverApp.java` | The runnable app: window, "New Maze" / "Start Solving" buttons, explorer picker, speed slider. |
| `explorers/RandomWalkExplorer.java` | Example strategy: picks a random direction every step. A baseline to compare against. |
| `explorers/WallFollowerExplorer.java` | Example strategy: right-hand-rule wall following. Always solves a perfect maze (just not efficiently). |

## The assignment (for students)

1. Create a new class in `mazegame/explorers/` that extends `BaseExplorer`.
2. Implement `solve()`. Inside it, you can use `moveUp()`, `moveDown()`,
   `moveLeft()`, `moveRight()` — each returns `true` if the move succeeded
   and `false` if you hit a wall (you did *not* move in that case). You can
   also call `canMoveUp()`, `canMoveDown()`, `canMoveLeft()`, `canMoveRight()`
   to test whether a move would succeed without performing it.
3. Use `isAtGoal()` to know when to stop, `getRow()`/`getCol()` to know
   where you are, and `getHint()` for the straight-line distance to the
   goal if your algorithm wants a heuristic (e.g. greedy best-first search).
4. Register your class in `MazeSolverApp.EXPLORERS` so it shows up in the
   dropdown:
   ```java
   EXPLORERS.put("My Algorithm", MyExplorer::new);
   ```
5. Run the app, pick your algorithm from the dropdown, and click
   **Start Solving**.

Ideas for algorithms of increasing difficulty: right-hand-rule (given as an
example), iterative depth-first search with your own explicit stack,
breadth-first search, Trémaux's algorithm, A* / greedy best-first search
using `getHint()`.

### Notes on the API

- `solve()` runs on its own thread, so a plain `while` loop with blocking
  move calls is fine — it won't freeze the UI.
- Moves are one square at a time in one of the 4 cardinal directions only.
- `canMoveUp/Down/Left/Right()` let you probe adjacent cells without moving,
  animating, or incrementing the move count.
- You never get the maze's wall grid directly. This is intentional: the
  exercise is about exploring and remembering, not reading a solved map.
- The trail shown on screen simply mirrors your current path: stepping
  forward marks a new square, stepping back onto the square you just came
  from unmarks it. It's a visualization only — you don't need to manage it
  yourself.

## Tests

`tests/SmokeTest.java` is a small headless sanity check (not needed to run
the game) that drives the engine directly and verifies a full solve run and
a rapid maze-reset stress case both complete without deadlocking. To run it:

```bash
javac -d out $(find src tests -name "*.java")
java -cp out mazegame.SmokeTest
```
