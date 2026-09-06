package mazegame;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the maze grid, the visited trail, a fading exploration overlay,
 * and a smoothly-animated sprite. Supports mazes larger than the window via
 * a soft-follow camera (critically-damped spring on the view rectangle) plus
 * manual pan/zoom.
 *
 * Cells on even indices (the "graph lines") are drawn thin (walls / openings);
 * cells on odd indices (the logical rooms) are drawn thicker.
 */
public class MazePanel extends JPanel {

    private static final int MAX_ANIMATION_STEPS = 20;

    /** Pixel size of logical room cells (odd row/col indices). */
    static final int ROOM_SIZE = 26;

    /** Pixel thickness of wall/opening cells (even row/col indices). */
    static final int WALL_THICKNESS = 4;

    // Exploration overlay
    private static final Color HIGHLIGHT_COLOR = new Color(0xFF, 0xC1, 0x07); // amber
    private static final Color EXPLORED_GREY = new Color(0x60, 0x60, 0x60);
    private static final int FADE_MOVES = 48;
    private static final int RECENT_N = 48;

    // Camera
    private static final double CAMERA_OMEGA = 3.5;
    private static final int MIN_VIEW_CELLS = 18;
    private static final int PADDING_CELLS = 4;
    private static final double MAX_ZOOM = 1.25;

    private Maze maze;
    private Deque<Cell> pathStack;

    // Cumulative pixel offsets
    private int[] colX;
    private int[] rowY;
    private int totalWidth;
    private int totalHeight;

    private BufferedImage backgroundCache;
    private BufferedImage trailCache;
    private int trailCachePathSize = -1;

    // Exploration overlay (world-space)
    private BufferedImage exploreSurface;
    private final Map<Cell, Integer> explored = new HashMap<>();
    private int exploreGen = 0;
    private final Set<Cell> fading = new HashSet<>();
    private final Deque<Cell> recent = new ArrayDeque<>();
    private final Set<Cell> frontier = new HashSet<>();
    private final Object exploreLock = new Object();

    // Sprite
    private double spriteX, spriteY;
    private Cell spriteCell;
    private boolean showSprite = true;

    // Camera state (view rectangle edges in world pixels)
    private double viewLeft, viewRight, viewTop, viewBottom;
    private double velLeft, velRight, velTop, velBottom;
    private double camX, camY, zoom = 1.0;
    private double panX, panY;
    private boolean snapCamera = true;
    private boolean overviewMode = false;
    private boolean debugCamera = false;
    private double cameraOmegaScale = 1.0;
    private int lastViewportW = 800;
    private int lastViewportH = 600;
    private long lastCameraNanos = 0;

    // Manual pan/zoom
    private boolean dragging = false;
    private Point dragLast;

    private volatile int externalAnimationDurationMs = 150;

    // Preferred viewport (window) size - maze may be larger
    private static final int PREFERRED_VIEW_W = 1064;
    private static final int PREFERRED_VIEW_H = 720;

    public MazePanel() {
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(PREFERRED_VIEW_W, PREFERRED_VIEW_H));
        setFocusable(true);

        // Mouse pan
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    dragging = true;
                    dragLast = e.getPoint();
                    requestFocusInWindow();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    dragging = false;
                    dragLast = null;
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging && dragLast != null) {
                    int dx = e.getX() - dragLast.x;
                    int dy = e.getY() - dragLast.y;
                    panX -= dx / Math.max(0.01, zoom);
                    panY -= dy / Math.max(0.01, zoom);
                    dragLast = e.getPoint();
                    repaint();
                }
            }
        });
        // Mouse-wheel zoom
        addMouseWheelListener(e -> {
            double factor = e.getWheelRotation() < 0 ? 1.12 : 1.0 / 1.12;
            double cx = 0.5 * (viewLeft + viewRight);
            double cy = 0.5 * (viewTop + viewBottom);
            double w = (viewRight - viewLeft) / factor;
            double h = (viewBottom - viewTop) / factor;
            double minW = lastViewportW / Math.max(MAX_ZOOM, 0.01);
            double maxW = totalWidth > 0 ? totalWidth * 1.01 : w;
            w = Math.max(minW, Math.min(maxW, w));
            double aspect = lastViewportW / (double) Math.max(1, lastViewportH);
            h = w / aspect;
            viewLeft = cx - w * 0.5;
            viewRight = cx + w * 0.5;
            viewTop = cy - h * 0.5;
            viewBottom = cy + h * 0.5;
            velLeft = velRight = velTop = velBottom = 0.0;
            clampViewToMaze(lastViewportW, lastViewportH);
            syncCamFromView(lastViewportW, lastViewportH);
            repaint();
        });

        // Continuous camera / fade tick
        Timer cameraTimer = new Timer(16, e -> {
            updateCameraAndFade();
            repaint();
        });
        cameraTimer.start();
    }

    // ------------------------------------------------------------------
    // Public API used by engine / app
    // ------------------------------------------------------------------

    void setEngineState(Maze maze, Deque<Cell> pathStack, Cell startCell) {
        this.maze = maze;
        this.pathStack = pathStack;
        rebuildGeometry();
        rebuildBackgroundCache();
        invalidateTrailCache();
        resetSprite(startCell);

        synchronized (exploreLock) {
            explored.clear();
            exploreGen = 0;
            fading.clear();
            recent.clear();
            frontier.clear();
        }
        exploreSurface = null;
        markExplored(startCell);

        showSprite = true;
        panX = 0;
        panY = 0;
        snapCamera = true;
        overviewMode = false;

        // Initial view around start
        Point2D center = cellCenter(startCell);
        double initBox = ROOM_SIZE * Math.max(MIN_VIEW_CELLS, 12);
        int vw = Math.max(1, lastViewportW);
        int vh = Math.max(1, lastViewportH);
        double halfW = initBox * 0.5;
        double halfH = initBox * 0.5 * (vh / (double) vw);
        viewLeft = center.x - halfW;
        viewRight = center.x + halfW;
        viewTop = center.y - halfH;
        viewBottom = center.y + halfH;
        velLeft = velRight = velTop = velBottom = 0;
        syncCamFromView(vw, vh);
        clampViewToMaze(vw, vh);
        syncCamFromView(vw, vh);

        revalidate();
        repaint();
    }

    void setAnimationDurationMs(int ms) {
        this.externalAnimationDurationMs = Math.max(0, ms);
    }

    void setCameraOmegaScale(double scale) {
        this.cameraOmegaScale = Math.max(0.25, Math.min(3.0, scale));
    }

    void markExplored(Cell cell) {
        if (cell == null) return;
        synchronized (exploreLock) {
            exploreGen++;
            explored.put(cell, exploreGen);
            fading.add(cell);
            recent.addLast(cell);
            while (recent.size() > RECENT_N * 2) recent.removeFirst();
        }
        paintExploredCell(cell, HIGHLIGHT_COLOR);
    }

    void markExploredMany(List<Cell> cells) {
        if (cells == null) return;
        for (Cell c : cells) markExplored(c);
    }

    void setFrontier(Collection<Cell> cells) {
        synchronized (exploreLock) {
            frontier.clear();
            if (cells != null) frontier.addAll(cells);
        }
    }

    void setShowSprite(boolean show) {
        this.showSprite = show;
        repaint();
    }

    void resetPan() {
        panX = 0;
        panY = 0;
        repaint();
    }

    boolean toggleOverview() {
        overviewMode = !overviewMode;
        if (overviewMode) {
            panX = 0;
            panY = 0;
            snapCamera = true;
        }
        return overviewMode;
    }

    boolean toggleDebugCamera() {
        debugCamera = !debugCamera;
        return debugCamera;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private void rebuildGeometry() {
        int cols = maze.getCols();
        int rows = maze.getRows();
        colX = new int[cols + 1];
        rowY = new int[rows + 1];
        colX[0] = 0;
        for (int c = 0; c < cols; c++) {
            int w = (c % 2 == 0) ? WALL_THICKNESS : ROOM_SIZE;
            colX[c + 1] = colX[c] + w;
        }
        totalWidth = colX[cols];
        rowY[0] = 0;
        for (int r = 0; r < rows; r++) {
            int h = (r % 2 == 0) ? WALL_THICKNESS : ROOM_SIZE;
            rowY[r + 1] = rowY[r] + h;
        }
        totalHeight = rowY[rows];
    }

    private int cellWidth(int col) {
        return colX[col + 1] - colX[col];
    }

    private int cellHeight(int row) {
        return rowY[row + 1] - rowY[row];
    }

    private void rebuildBackgroundCache() {
        backgroundCache = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = backgroundCache.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int r = 0; r < maze.getRows(); r++) {
            for (int c = 0; c < maze.getCols(); c++) {
                boolean open = maze.isOpen(r, c);
                g.setColor(open ? Color.BLACK : new Color(0x00FFFF));
                g.fillRect(colX[c], rowY[r], cellWidth(c), cellHeight(r));
            }
        }
        drawMarker(g, maze.getStart(), new Color(0x4CAF50), "S");
        drawMarker(g, maze.getGoal(), new Color(0xFFB300), "G");
        g.dispose();
    }

    void invalidateTrailCache() {
        trailCache = null;
        trailCachePathSize = -1;
    }

    private void ensureTrailCache() {
        if (pathStack == null) return;
        int size = pathStack.size();
        if (trailCache != null && trailCachePathSize == size) return;
        trailCache = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = trailCache.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x9FD8FF));
        int idx = 0;
        for (Cell c : pathStack) {
            if (idx > 0) {
                int pad = Math.max(2, ROOM_SIZE / 6);
                g.fillRoundRect(
                        colX[c.col] + pad,
                        rowY[c.row] + pad,
                        cellWidth(c.col) - 2 * pad,
                        cellHeight(c.row) - 2 * pad,
                        6, 6);
            }
            idx++;
        }
        g.dispose();
        trailCachePathSize = size;
    }

    void resetSprite(Cell cell) {
        this.spriteCell = cell;
        Point2D p = cellCenter(cell);
        this.spriteX = p.x;
        this.spriteY = p.y;
        invalidateTrailCache();
        repaint();
    }

    private static class Point2D {
        final double x, y;
        Point2D(double x, double y) { this.x = x; this.y = y; }
    }

    private Point2D cellCenter(Cell c) {
        return new Point2D(
                colX[c.col] + cellWidth(c.col) / 2.0,
                rowY[c.row] + cellHeight(c.row) / 2.0);
    }

    // ------------------------------------------------------------------
    // Exploration overlay helpers
    // ------------------------------------------------------------------

    private void ensureExploreSurface() {
        if (exploreSurface != null) return;
        if (totalWidth <= 0 || totalHeight <= 0) return;
        exploreSurface = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
    }

    private void paintExploredCell(Cell cell, Color color) {
        ensureExploreSurface();
        if (exploreSurface == null) return;
        int pad = Math.max(1, ROOM_SIZE / 6);
        int x = colX[cell.col] + pad;
        int y = rowY[cell.row] + pad;
        int w = cellWidth(cell.col) - 2 * pad;
        int h = cellHeight(cell.row) - 2 * pad;
        if (w <= 0 || h <= 0) return;
        Graphics2D g = exploreSurface.createGraphics();
        g.setColor(color);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    private void updateExplorationFade() {
        List<Cell> fadingCopy;
        Map<Cell, Integer> exploredCopy;
        int gen;
        synchronized (exploreLock) {
            if (fading.isEmpty()) return;
            fadingCopy = new ArrayList<>(fading);
            exploredCopy = new HashMap<>(explored);
            gen = exploreGen;
        }
        List<Cell> done = new ArrayList<>();
        for (Cell cell : fadingCopy) {
            Integer markedAt = exploredCopy.get(cell);
            if (markedAt == null) {
                done.add(cell);
                continue;
            }
            int age = gen - markedAt;
            if (age >= FADE_MOVES) {
                paintExploredCell(cell, EXPLORED_GREY);
                done.add(cell);
            } else {
                double raw = 1.0 - age / (double) FADE_MOVES;
                double intensity = raw * raw;
                Color c = lerpColor(EXPLORED_GREY, HIGHLIGHT_COLOR, intensity);
                paintExploredCell(cell, c);
            }
        }
        if (!done.isEmpty()) {
            synchronized (exploreLock) {
                fading.removeAll(done);
            }
        }
    }

    private static Color lerpColor(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, g, bl);
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private void updateCameraAndFade() {
        if (maze == null || totalWidth <= 0) return;
        int vw = Math.max(1, getWidth());
        int vh = Math.max(1, getHeight());
        lastViewportW = vw;
        lastViewportH = vh;

        long now = System.nanoTime();
        double dt = lastCameraNanos == 0 ? 0.016 : (now - lastCameraNanos) / 1e9;
        lastCameraNanos = now;
        dt = Math.max(0, Math.min(dt, 0.05));

        updateExplorationFade();
        updateCamera(vw, vh, dt);
    }

    private void updateCamera(int viewportW, int viewportH, double dt) {
        List<Cell> focusCells;
        synchronized (exploreLock) {
            if (!frontier.isEmpty()) {
                focusCells = new ArrayList<>(frontier);
            } else {
                focusCells = new ArrayList<>();
                Set<Cell> seen = new HashSet<>();
                Iterator<Cell> it = recent.descendingIterator();
                while (it.hasNext() && focusCells.size() < RECENT_N) {
                    Cell c = it.next();
                    if (seen.add(c)) focusCells.add(c);
                }
            }
        }

        double tl, tr, tt, tb;
        if (overviewMode) {
            tl = 0; tr = totalWidth; tt = 0; tb = totalHeight;
        } else if (focusCells.isEmpty()) {
            double pad = ROOM_SIZE * PADDING_CELLS;
            tl = spriteX - pad; tr = spriteX + pad;
            tt = spriteY - pad; tb = spriteY + pad;
        } else {
            tl = Double.POSITIVE_INFINITY; tr = Double.NEGATIVE_INFINITY;
            tt = Double.POSITIVE_INFINITY; tb = Double.NEGATIVE_INFINITY;
            for (Cell cell : focusCells) {
                Point2D p = cellCenter(cell);
                double halfW = cellWidth(cell.col) * 0.5 + ROOM_SIZE * PADDING_CELLS;
                double halfH = cellHeight(cell.row) * 0.5 + ROOM_SIZE * PADDING_CELLS;
                tl = Math.min(tl, p.x - halfW);
                tr = Math.max(tr, p.x + halfW);
                tt = Math.min(tt, p.y - halfH);
                tb = Math.max(tb, p.y + halfH);
            }
            double pad = ROOM_SIZE * PADDING_CELLS;
            tl = Math.min(tl, spriteX - pad);
            tr = Math.max(tr, spriteX + pad);
            tt = Math.min(tt, spriteY - pad);
            tb = Math.max(tb, spriteY + pad);
        }

        tl = Math.max(0, tl);
        tt = Math.max(0, tt);
        tr = Math.min(totalWidth, tr);
        tb = Math.min(totalHeight, tb);
        if (tr < tl) { double tmp = tl; tl = tr; tr = tmp; }
        if (tb < tt) { double tmp = tt; tt = tb; tb = tmp; }

        double minSpan = ROOM_SIZE * MIN_VIEW_CELLS;
        if (tr - tl < minSpan) {
            double mid = 0.5 * (tl + tr);
            tl = mid - minSpan * 0.5;
            tr = mid + minSpan * 0.5;
        }
        if (tb - tt < minSpan) {
            double mid = 0.5 * (tt + tb);
            tt = mid - minSpan * 0.5;
            tb = mid + minSpan * 0.5;
        }

        // Match aspect ratio of the viewport
        double targetW = tr - tl;
        double targetH = tb - tt;
        double aspect = viewportW / (double) Math.max(1, viewportH);
        if (targetW / targetH < aspect) {
            double mid = 0.5 * (tl + tr);
            targetW = targetH * aspect;
            tl = mid - targetW * 0.5;
            tr = mid + targetW * 0.5;
        } else {
            double mid = 0.5 * (tt + tb);
            targetH = targetW / aspect;
            tt = mid - targetH * 0.5;
            tb = mid + targetH * 0.5;
        }

        if (snapCamera) {
            viewLeft = tl; viewRight = tr; viewTop = tt; viewBottom = tb;
            velLeft = velRight = velTop = velBottom = 0;
            snapCamera = false;
        } else {
            double omega = CAMERA_OMEGA * cameraOmegaScale;
            double[] left = springStep(viewLeft, velLeft, tl, omega, dt);
            double[] right = springStep(viewRight, velRight, tr, omega, dt);
            double[] top = springStep(viewTop, velTop, tt, omega, dt);
            double[] bottom = springStep(viewBottom, velBottom, tb, omega, dt);
            viewLeft = left[0]; velLeft = left[1];
            viewRight = right[0]; velRight = right[1];
            viewTop = top[0]; velTop = top[1];
            viewBottom = bottom[0]; velBottom = bottom[1];
        }

        clampViewToMaze(viewportW, viewportH);
        syncCamFromView(viewportW, viewportH);
    }

    private static double[] springStep(double pos, double vel, double target, double omega, double dt) {
        if (Math.abs(pos - target) < 0.5 && Math.abs(vel) < 2.0) {
            return new double[]{target, 0.0};
        }
        double accel = -2.0 * omega * vel - (omega * omega) * (pos - target);
        vel = vel + accel * dt;
        pos = pos + vel * dt;
        return new double[]{pos, vel};
    }

    private void clampViewToMaze(int viewportW, int viewportH) {
        if (totalWidth <= 0 || totalHeight <= 0) return;
        double w = viewRight - viewLeft;
        double h = viewBottom - viewTop;
        if (w > totalWidth * 1.01) {
            double mid = 0.5 * (viewLeft + viewRight);
            w = totalWidth * 1.01;
            viewLeft = mid - w * 0.5;
            viewRight = mid + w * 0.5;
        }
        if (h > totalHeight * 1.01) {
            double mid = 0.5 * (viewTop + viewBottom);
            h = totalHeight * 1.01;
            viewTop = mid - h * 0.5;
            viewBottom = mid + h * 0.5;
        }
        if (viewLeft < 0) { viewRight -= viewLeft; viewLeft = 0; }
        if (viewTop < 0) { viewBottom -= viewTop; viewTop = 0; }
        if (viewRight > totalWidth) { viewLeft -= (viewRight - totalWidth); viewRight = totalWidth; }
        if (viewBottom > totalHeight) { viewTop -= (viewBottom - totalHeight); viewBottom = totalHeight; }
        if (viewLeft < 0) viewLeft = 0;
        if (viewTop < 0) viewTop = 0;
    }

    private void syncCamFromView(int viewportW, int viewportH) {
        double w = Math.max(1e-6, viewRight - viewLeft);
        double h = Math.max(1e-6, viewBottom - viewTop);
        camX = 0.5 * (viewLeft + viewRight);
        camY = 0.5 * (viewTop + viewBottom);
        zoom = Math.min(viewportW / w, viewportH / h);
        zoom = Math.max(0.05, zoom);
        if (zoom > MAX_ZOOM) {
            double scale = zoom / MAX_ZOOM;
            camX = 0.5 * (viewLeft + viewRight);
            camY = 0.5 * (viewTop + viewBottom);
            viewLeft = camX - (camX - viewLeft) * scale;
            viewRight = camX + (viewRight - camX) * scale;
            viewTop = camY - (camY - viewTop) * scale;
            viewBottom = camY + (viewBottom - camY) * scale;
            zoom = MAX_ZOOM;
            clampViewToMaze(viewportW, viewportH);
            w = Math.max(1e-6, viewRight - viewLeft);
            h = Math.max(1e-6, viewBottom - viewTop);
            camX = 0.5 * (viewLeft + viewRight);
            camY = 0.5 * (viewTop + viewBottom);
            zoom = Math.min(viewportW / w, viewportH / h);
            zoom = Math.max(0.05, Math.min(zoom, MAX_ZOOM));
        }
    }

    // ------------------------------------------------------------------
    // Animation
    // ------------------------------------------------------------------

    void animateMove(Cell from, Cell to, CountDownLatch latch) {
        Point2D toPx = cellCenter(to);
        spriteCell = to;
        int steps = stepsForDuration();
        if (steps <= 1) {
            spriteX = toPx.x;
            spriteY = toPx.y;
            repaint();
            latch.countDown();
            return;
        }
        Point2D fromPx = cellCenter(from);
        int perStepDelay = Math.max(1, externalAnimationDurationMs / steps);
        Timer[] timerHolder = new Timer[1];
        int[] step = {0};
        Timer timer = new Timer(perStepDelay, null);
        timer.addActionListener(e -> {
            step[0]++;
            double t = Math.min(1.0, step[0] / (double) steps);
            t = easeInOut(t);
            spriteX = fromPx.x + (toPx.x - fromPx.x) * t;
            spriteY = fromPx.y + (toPx.y - fromPx.y) * t;
            repaint();
            if (step[0] >= steps) {
                timerHolder[0].stop();
                spriteX = toPx.x;
                spriteY = toPx.y;
                repaint();
                latch.countDown();
            }
        });
        timerHolder[0] = timer;
        timer.start();
    }

    void animateBump(Cell at, Direction dir, CountDownLatch latch) {
        int steps = stepsForDuration();
        if (steps <= 1) {
            latch.countDown();
            return;
        }
        Point2D center = cellCenter(at);
        double nudge = ROOM_SIZE * 0.28;
        double targetX = center.x + dir.dCol * nudge;
        double targetY = center.y + dir.dRow * nudge;
        int totalSteps = Math.max(2, steps / 2);
        int perStepDelay = Math.max(1, externalAnimationDurationMs / totalSteps / 2);
        Timer[] timerHolder = new Timer[1];
        int[] step = {0};
        Timer timer = new Timer(perStepDelay, null);
        timer.addActionListener(e -> {
            step[0]++;
            double t = step[0] / (double) totalSteps;
            double phase = t <= 0.5 ? (t / 0.5) : (1 - (t - 0.5) / 0.5);
            spriteX = center.x + (targetX - center.x) * phase;
            spriteY = center.y + (targetY - center.y) * phase;
            repaint();
            if (step[0] >= totalSteps) {
                timerHolder[0].stop();
                spriteX = center.x;
                spriteY = center.y;
                repaint();
                latch.countDown();
            }
        });
        timerHolder[0] = timer;
        timer.start();
    }

    void animateHop(Cell from, Cell to, CountDownLatch latch) {
        // Instant hop for non-adjacent moves (used by visit API)
        Point2D toPx = cellCenter(to);
        spriteCell = to;
        spriteX = toPx.x;
        spriteY = toPx.y;
        repaint();
        latch.countDown();
    }

    private int stepsForDuration() {
        if (externalAnimationDurationMs <= 4) return 1;
        int steps = externalAnimationDurationMs / 4;
        return Math.max(1, Math.min(MAX_ANIMATION_STEPS, steps));
    }

    private static double easeInOut(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (maze == null || backgroundCache == null) return;

        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int vw = getWidth();
        int vh = getHeight();

        // World -> screen transform: centre the view rect, apply zoom, add manual pan
        double z = zoom;
        double ox = vw * 0.5 - (camX + panX) * z;
        double oy = vh * 0.5 - (camY + panY) * z;

        AffineTransform old = g.getTransform();
        g.translate(ox, oy);
        g.scale(z, z);

        // Background (walls + S/G)
        g.drawImage(backgroundCache, 0, 0, null);

        // Exploration overlay
        if (exploreSurface != null) {
            g.drawImage(exploreSurface, 0, 0, null);
        }

        // Trail
        ensureTrailCache();
        if (trailCache != null) {
            g.drawImage(trailCache, 0, 0, null);
        }

        // Sprite
        if (showSprite) {
            double radius = ROOM_SIZE * 0.32;
            g.setColor(new Color(0xE53935));
            Ellipse2D dot = new Ellipse2D.Double(spriteX - radius, spriteY - radius,
                    radius * 2, radius * 2);
            g.fill(dot);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f / (float) Math.max(0.01, z)));
            g.draw(dot);
        }

        // Debug focus box
        if (debugCamera) {
            g.setColor(new Color(255, 0, 255, 120));
            g.setStroke(new BasicStroke(2f / (float) Math.max(0.01, z)));
            g.drawRect((int) viewLeft, (int) viewTop,
                    (int) (viewRight - viewLeft), (int) (viewBottom - viewTop));
        }

        g.setTransform(old);
    }

    private void drawMarker(Graphics2D g, Cell c, Color color, String label) {
        int pad = Math.max(2, ROOM_SIZE / 8);
        int x = colX[c.col] + pad;
        int y = rowY[c.row] + pad;
        int w = cellWidth(c.col) - 2 * pad;
        int h = cellHeight(c.row) - 2 * pad;
        g.setColor(color);
        g.fillRoundRect(x, y, w, h, 6, 6);
        g.setColor(Color.WHITE);
        g.setFont(getFont().deriveFont(Font.BOLD, ROOM_SIZE * 0.5f));
        FontMetrics fm = g.getFontMetrics();
        int tx = colX[c.col] + (cellWidth(c.col) - fm.stringWidth(label)) / 2;
        int ty = rowY[c.row] + (cellHeight(c.row) + fm.getAscent()) / 2 - 2;
        g.drawString(label, tx, ty);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(PREFERRED_VIEW_W, PREFERRED_VIEW_H);
    }
}
