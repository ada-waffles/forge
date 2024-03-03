package forge.view;

import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.swing.BorderFactory;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import forge.Singletons;
import forge.gamemodes.match.HostedMatch;
import forge.gui.framework.SDisplayUtil;
import forge.gui.framework.SResizingUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.sound.SoundSystem;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.Colors;
import forge.toolbox.FSkin.CompoundSkinBorder;
import forge.toolbox.FSkin.LineSkinBorder;
import forge.toolbox.FSkin.SkinnedFrame;
import forge.util.OSUtil;
import forge.util.OSUtil.OS;
import forge.util.ThreadUtil;

@SuppressWarnings("serial")
public class FFrame extends SkinnedFrame implements ITitleBarOwner {
    private static final int borderThickness = 3;
    private Point locBeforeMove;
    private Dimension sizeBeforeResize;
    private Point mouseDownLoc;
    private boolean moveInProgress;
    private int resizeCursor;
    private FTitleBarBase titleBar;
    private boolean hideBorder, lockTitleBar, hideTitleBar, isMainFrame, paused;
    private Rectangle normalBounds;

    /**
     * On some platforms, state such as maximized is lost when minimizing a window,
     * so store the state here when minimizing to restore later
     */
    private int preMinimizeState = 0;

    /**
     * Used to track full-screen state on macOS, since its APIs do not allow polling the current state of the window
     */
    private Boolean macosFullScreen = null;

    /**
     * If true, setFullScreen(true) was called before setVisible(true), which does not work on macOS.
     * The window will be made full-screen once it is made visible.
     */
    private boolean macosFullScreenPending = false;

    /**
     * Stores what the bounds were before entering macOS fullscreen mode.
     * This is separate from normalBounds because "maximized" on macOS doesn't really exist as a separate state.
     */
    private Rectangle macosPreFullScreenBounds = null;

    public FFrame() {
        setUndecorated(true);
    }

    public void initialize(final FTitleBarBase titleBar0) {
        this.isMainFrame = (FView.SINGLETON_INSTANCE.getFrame() == this);

        // Frame border
        this.hideBorder = true; //ensure border shown when window layout loaded
        this.hideTitleBar = true; //ensure titlebar shown when window layout loaded
        this.lockTitleBar = this.isMainFrame && FModel.getPreferences().getPrefBoolean(FPref.UI_LOCK_TITLE_BAR);
        addResizeSupport();

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(final WindowEvent e) {
                resume(); //resume music when main frame regains focus
            }

            @Override
            public void windowDeactivated(final WindowEvent e) {
                if (e.getOppositeWindow() == null) {
                    pause(); //pause music when main frame loses focus to outside application

                    //Minimize if switching from Full Screen Forge to outside application window, if allowed
                    if (isFullScreen() && !macosFullScreenRules()) {
                        setMinimized(true);
                    }
                }
            }
        });


        addWindowStateListener((final WindowEvent e) -> {
            final boolean wasMinimized = (e.getOldState() & Frame.ICONIFIED) == Frame.ICONIFIED;
            final boolean isMinimized = (e.getNewState() & Frame.ICONIFIED) == Frame.ICONIFIED;
            if (wasMinimized && !isMinimized) {
                setExtendedState(preMinimizeState);
            }

            //Any time the window is (un)maximized, (un)minimized, moved, or resized, update the border and title bar.
            updateBorder();
            updateTitleBar();
        });

        //There is unfortunately no direct event for the fullscreen state, but it's a reasonable assumption that
        //if the user/window manager (un-)full-screens the window outside of our control that the window's bounds
        //will change as a result (and hopefully that will fire the ComponentListener).
        //To avoid lots of wasted cycles on continuous changes, these event listeners need to be debounced.
        //Use a trailing debounce so the window reflects the full-screen state change immediately.
        addComponentListener(new ComponentAdapter() {
            private final long DEBOUNCE_INTERVAL_MS = 750;
            private volatile long lastCalled = 0;
            
            private void debouncedUpdate() {
                final long callTime = System.currentTimeMillis();
                
                if (callTime - lastCalled > DEBOUNCE_INTERVAL_MS) {
                    updateBorder();
                    updateTitleBar();
                }
                
                lastCalled = callTime;
            }
            
            @Override
            public void componentMoved(final ComponentEvent e) {
                debouncedUpdate();
            }
            
            @Override
            public void componentResized(final ComponentEvent e) {
                debouncedUpdate();
            }
        });

        //If we're running on a Mac, hook up the macOS-specific fullscreen APIs
        if (OSUtil.detectOS() == OS.MAC_OS) {
            try {
                //The native fullscreen APIs on macOS are available through Apple classes which only exist on macOS.
                //Accessing them via reflection allows the code to compile cross-platform.
                //See: https://stackoverflow.com/a/30090377
                final Class<?> fullScreenUtilitiesClass =
                    Class.forName("com.apple.eawt.FullScreenUtilities");
                final Class<?> fullScreenListenerInterface =
                    Class.forName("com.apple.eawt.FullScreenListener");

                //macOS fullscreen must be enabled before it can be used. This only needs to be done once.
                final Method setWindowCanFullScreen = fullScreenUtilitiesClass.getMethod(
                    "setWindowCanFullScreen",
                    Window.class,
                    boolean.class
                );
                setWindowCanFullScreen.invoke(fullScreenUtilitiesClass, this, true);

                //Add an event listener so we know if we're in fullscreen currently or not
                //(There's unfortunately no way to check on-demand)
                final Method addFullScreenListenerMethod = fullScreenUtilitiesClass.getMethod(
                    "addFullScreenListenerTo",
                    Window.class,
                    fullScreenListenerInterface
                );

                final Object proxySubject = new Object();
                final Object fullScreenListener = Proxy.newProxyInstance(
                    fullScreenListenerInterface.getClassLoader(),
                    new Class[] {fullScreenListenerInterface},
                    (proxy, method, args) -> {
                        //Handle the java.lang.Object methods
                        if (method.getDeclaringClass() == Object.class) return method.invoke(proxySubject, args);

                        switch (method.getName()) {
                            //Using "entering" and "exiting" events rather than "entered" and "exited"
                            //because it makes the animations look better, if still sorta jank
                            case "windowEnteringFullScreen":
                                macosFullScreen = true;

                                //The macOS fullscreen mechanism strangely does not
                                //automatically extend the bounds of the window,
                                //so we need to manually set the bounds to the size of the screen
                                //when entering fullscreen mode, and then restore them on the way out.
                                //Doing this here in the event listener instead of the setFullscreen method
                                //so that the macosFullScreen flag will be accurate
                                //and normalBounds will not be incorrectly overwritten.
                                macosPreFullScreenBounds = getBounds();
                                final Rectangle fullScreenBounds =
                                    SDisplayUtil.getGraphicsDevice(this).getDefaultConfiguration().getBounds();
                                setBounds(fullScreenBounds);

                                break;
                            case "windowExitingFullScreen":
                                macosFullScreen = false;

                                if (macosPreFullScreenBounds != null) {
                                    setBounds(macosPreFullScreenBounds);
                                    macosPreFullScreenBounds = null;
                                }
                                else {
                                    //Fallback, just in case
                                    applyNormalBounds();
                                }

                                break;
                        }

                        updateBorder();
                        updateTitleBar();

                        return null; //All the listener methods return void
                    }
                );

                addFullScreenListenerMethod.invoke(fullScreenUtilitiesClass, this, fullScreenListener);

                //If all was successful, then initialize macOS fullscreen state to false
                macosFullScreen = false;
            }
            catch (
                ClassNotFoundException
                | NoSuchMethodException
                | SecurityException
                | IllegalAccessException
                | InvocationTargetException ex
            ) {
                System.err.println("Unable to initialize macOS fullscreen API. Will attempt to use Java fullscreen.");
                ex.printStackTrace();
            }
        }

        // Title bar
        this.titleBar = titleBar0;
        addMoveSupport();
    }

    @Override
    public void setVisible(final boolean visible) {
        super.setVisible(visible);

        if (macosFullScreenPending) {
            //If the window's non-full-screen state is maximized, Swing needs a moment to correctly position the window
            //before full-screening it. Otherwise, it will be positioned wrong when exiting full-screen.
            //Also, this makes the opening fullscreen animation look more correct.
            ThreadUtil.delay(100, () -> {
                SwingUtilities.invokeLater(() -> {
                    setFullScreen(true);
                    macosFullScreenPending = false;
                });
            });
        }
    }

    @Override
    public void setExtendedState(final int state) {
        if (OSUtil.detectOS() == OS.WINDOWS && (state & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
            //Maximizing an undecorated Swing window on Windows does not correctly respect the taskbar.
            //However, you can tell Swing what the maximized bounds for the window should be.
            //See: https://stackoverflow.com/questions/6422931/why-jframe-hides-taskbar-when-maximized
            setMaximizedBounds(SDisplayUtil.getScreenMaximizedBounds(getBounds()));
        }

        super.setExtendedState(state);
    }

    private void pause() {
        if (paused || !isMainFrame) { return; }

        // Pause the sound
        SoundSystem.instance.pause();

        // Pause all hosted matches
        for (final HostedMatch hostedMatch : Singletons.getControl().getCurrentMatches()) {
            hostedMatch.pause();
        }
        paused = true;
    }

    private void resume() {
        if (!paused || !isMainFrame) { return; }

        // Resume the sound
        SoundSystem.instance.resume();

        // Resume all hosted matches
        for (final HostedMatch hostedMatch : Singletons.getControl().getCurrentMatches()) {
            hostedMatch.resume();
        }
        paused = false;
    }

    public FTitleBarBase getTitleBar() {
        return this.titleBar;
    }

    @Override
    public boolean getLockTitleBar() {
        return this.lockTitleBar;
    }

    @Override
    public void setLockTitleBar(final boolean lockTitleBar0) {
        if (this.lockTitleBar == lockTitleBar0) { return; }
        this.lockTitleBar = lockTitleBar0;
        if (this.isMainFrame) {
            final ForgePreferences prefs = FModel.getPreferences();
            prefs.setPref(FPref.UI_LOCK_TITLE_BAR, lockTitleBar0);
            prefs.save();
        }
        updateTitleBar();
    }

    public boolean isTitleBarHidden() {
        return this.hideTitleBar;
    }

    private void updateTitleBar() {
        this.titleBar.updateButtons();
        final boolean shouldHideTitleBar = !macosFullScreenRules() && isFullScreen() && !getLockTitleBar();
        if (this.hideTitleBar == shouldHideTitleBar) return;
        this.hideTitleBar = shouldHideTitleBar;
        this.titleBar.setVisible(!this.hideTitleBar);
        if (this.isMainFrame) {
            SResizingUtil.resizeWindow(); //ensure window layout updated to account for titlebar visibility change
        }
    }

    @Override
    public void setTitle(final String title) {
        super.setTitle(title);
        if (this.titleBar != null) {
            this.titleBar.setTitle(title);
        }
    }

    @Override
    public void setIconImage(final Image image) {
        super.setIconImage(image);
        if (this.titleBar != null) {
            this.titleBar.setIconImage(image);
        }
    }

    //ensure un-maximized if location or size changed
    @Override
    public void setLocation(final Point point) {
        resetState();
        super.setLocation(point);
    }
    @Override
    public void setLocation(final int x, final int y) {
        resetState();
        super.setLocation(x, y);
    }
    @Override
    public void setSize(final Dimension size) {
        resetState();
        super.setSize(size);
    }
    @Override
    public void setSize(final int width, final int height) {
        resetState();
        super.setSize(width, height);
    }

    private void resetState() {
        setExtendedState(Frame.NORMAL);
        setFullScreen(false);
    }

    public void setWindowLayout(
        final int x,
        final int y,
        final int width,
        final int height,
        final boolean maximized,
        final boolean fullScreen
    ) {
        setNormalBounds(new Rectangle(x, y, width, height));
        setMaximized(maximized);
        setFullScreen(fullScreen);
    }

    /**
     * @return the bounds used by this window when not maximized or full-screen
     */
    public Rectangle getNormalBounds() {
        return this.normalBounds;
    }

    /**
     * Sets the bounds the window will use when not maximized or fullscreen
     * 
     * @param bounds The bounds to apply
     */
    public void setNormalBounds(final Rectangle bounds) {
        normalBounds = bounds;
        
        if (!isMaximized() && !isFullScreen()) {
            applyNormalBounds();
        }
    }

    /**
     * Updates the window's normal bounds from the window manager if the window is currently normal
     */
    public void updateNormalBounds() {
        if (isNormal()) {
            normalBounds = this.getBounds();
        }
    }

    /**
     * Sets the window's bounds to equal its normal bounds (if not null)
     */
    public void applyNormalBounds() {
        if (normalBounds != null) {
            setBounds(normalBounds);
        }
    }

    @Override
    public boolean isMinimized() {
        return (getExtendedState() & Frame.ICONIFIED) == Frame.ICONIFIED;
    }

    @Override
    public void setMinimized(final boolean minimized0) {
        if (minimized0 == isMinimized()) return;

        if (minimized0) {
            preMinimizeState = getExtendedState();
            setExtendedState(preMinimizeState | Frame.ICONIFIED);
        }
        else {
            setExtendedState(preMinimizeState);
        }
    }

    @Override
    public boolean isMaximized() {
        return (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    @Override
    public void setMaximized(final boolean maximized0) {
        if (maximized0) {
            setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
        else {
            setExtendedState(getExtendedState() & ~Frame.MAXIMIZED_BOTH);
            applyNormalBounds();
        }
    }

    @Override
    public boolean isFullScreen() {
        if (macosFullScreen != null) return macosFullScreen;
        else return SDisplayUtil.windowIsFullScreen(this);
    }

    @Override
    public boolean macosFullScreenRules() {
        return macosFullScreen != null;
    }

    @Override
    public void setFullScreen(final boolean fullScreen0) {
        if (macosFullScreen != null) {
            if (macosFullScreen == fullScreen0) return;

            if (!isVisible() && fullScreen0) {
                macosFullScreenPending = true;
                return;
            }

            try {
                final Class<?> appleApplicationClass = Class.forName("com.apple.eawt.Application");
                final Method getApplication = appleApplicationClass.getMethod("getApplication");
                final Object application = getApplication.invoke(appleApplicationClass);
                final Method requestToggleFulLScreen =
                    application.getClass().getMethod("requestToggleFullScreen", Window.class);
                requestToggleFulLScreen.invoke(application, this);
            }
            catch (
                ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException ex
            ) {
                System.err.println("Unable to enter macOS fullscreen");
                ex.printStackTrace();
            }
        }
        else {
            //For some reason, setting fullscreen can also cause the maximized state to be set
            //(This mainly seems to happen when the window is fullscreen on launch),
            //So save and restore that state when going full-screen.
            final boolean wasMaximized = isMaximized();

            final boolean fullScreenChanged = SDisplayUtil.setFullScreenWindow(this, fullScreen0);

            if (fullScreenChanged) {
                setMaximized(wasMaximized);

                //If coming out of fullscreen, also restore the normal bounds
                if (!fullScreen0) {
                    applyNormalBounds();
                }
            }
        }
    }

    /**
     * @return whether the window is currently normal (that is, not minimized, maximized, or full-screen)
     */
    public boolean isNormal() {
        return getExtendedState() == Frame.NORMAL && !isFullScreen();
    }

    private void updateBorder() {
        if (isMinimized() || this.hideBorder == (isMaximized() || isFullScreen())) {
            return; //don't update border if minimized or border visibility wouldn't change
        }
        this.hideBorder = !this.hideBorder;
        if (this.hideBorder) {
            this.setBorder((Border)null);
        }
        else {
            this.setBorder(new CompoundSkinBorder(
                    BorderFactory.createLineBorder(Color.BLACK, 1),
                    new LineSkinBorder(FSkin.getColor(Colors.CLR_BORDERS), borderThickness - 1)));
        }
    }

    private void addMoveSupport() {
        this.titleBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && !isFullScreen()) { //don't allow moving or restore down when Full Screen
                    if (e.getClickCount() == 1) {
                        locBeforeMove = getLocation();
                        mouseDownLoc = e.getLocationOnScreen();
                    }
                    else {
                        setMaximized(!isMaximized());
                    }
                }
            }
            @Override
            public void mouseReleased(final MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    locBeforeMove = null;
                    mouseDownLoc = null;
                    moveInProgress = false;
                }
            }
        });
        this.titleBar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(final MouseEvent e) {
                if (mouseDownLoc != null) {
                    final Point loc = e.getLocationOnScreen();
                    final int dx = loc.x - mouseDownLoc.x;
                    final int dy = loc.y - mouseDownLoc.y;
                    if (!moveInProgress) {
                        if (isMaximized() && dx * dx + dy * dy < 25) {
                            //don't start frame move if maximized until you've moved the mouse at least than 5 pixels
                            return;
                        }
                        moveInProgress = true;
                    }
                    setLocation(locBeforeMove.x + dx, locBeforeMove.y + dy);
                }
            }
        });
    }

    private void setResizeCursor(final int resizeCursor0) {
        this.resizeCursor = resizeCursor0;
        this.getRootPane().setCursor(Cursor.getPredefinedCursor(resizeCursor0));
    }

    private void addResizeSupport() {
        final JRootPane resizeBorders = getRootPane();
        resizeBorders.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                if (resizeCursor != Cursor.DEFAULT_CURSOR && SwingUtilities.isLeftMouseButton(e)) {
                    locBeforeMove = getLocation();
                    sizeBeforeResize = getSize();
                    mouseDownLoc = e.getLocationOnScreen();
                }
            }
            @Override
            public void mouseReleased(final MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    locBeforeMove = null;
                    sizeBeforeResize = null;
                    mouseDownLoc = null;
                    setResizeCursor(Cursor.DEFAULT_CURSOR);
                }
            }
            @Override
            public void mouseExited(final MouseEvent e) {
                if (mouseDownLoc == null) {
                    setResizeCursor(Cursor.DEFAULT_CURSOR);
                }
            }
        });
        resizeBorders.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                if (mouseDownLoc == null && !isMaximized()) {
                    final int grabArea = borderThickness * 2;
                    final Point loc = e.getPoint();
                    if (loc.x < grabArea) {
                        if (loc.y < grabArea) {
                            setResizeCursor(Cursor.NW_RESIZE_CURSOR);
                        }
                        else if (loc.y >= getHeight() - grabArea) {
                            setResizeCursor(Cursor.SW_RESIZE_CURSOR);
                        }
                        else {
                            setResizeCursor(Cursor.W_RESIZE_CURSOR);
                        }
                    }
                    else if (loc.x >= getWidth() - grabArea) {
                        if (loc.y < grabArea) {
                            setResizeCursor(Cursor.NE_RESIZE_CURSOR);
                        }
                        else if (loc.y >= getHeight() - grabArea) {
                            setResizeCursor(Cursor.SE_RESIZE_CURSOR);
                        }
                        else {
                            setResizeCursor(Cursor.E_RESIZE_CURSOR);
                        }
                    }
                    else if (loc.y < grabArea) {
                        setResizeCursor(Cursor.N_RESIZE_CURSOR);
                    }
                    else if (loc.y >= getHeight() - grabArea) {
                        setResizeCursor(Cursor.S_RESIZE_CURSOR);
                    }
                    else {
                        setResizeCursor(Cursor.DEFAULT_CURSOR);
                    }
                }
            }
            @Override
            public void mouseDragged(final MouseEvent e) {
                if (mouseDownLoc == null) { return; }

                final Point loc = e.getLocationOnScreen();
                int dx = loc.x - mouseDownLoc.x;
                int dy = loc.y - mouseDownLoc.y;

                //determine new size based on resize direction
                int width = sizeBeforeResize.width;
                int height = sizeBeforeResize.height;
                switch (resizeCursor) {
                case Cursor.E_RESIZE_CURSOR:
                    width += dx;
                    break;
                case Cursor.W_RESIZE_CURSOR:
                    width -= dx;
                    break;
                case Cursor.S_RESIZE_CURSOR:
                    height += dy;
                    break;
                case Cursor.N_RESIZE_CURSOR:
                    height -= dy;
                    break;
                case Cursor.SE_RESIZE_CURSOR:
                    width += dx;
                    height += dy;
                    break;
                case Cursor.NE_RESIZE_CURSOR:
                    width += dx;
                    height -= dy;
                    break;
                case Cursor.SW_RESIZE_CURSOR:
                    width -= dx;
                    height += dy;
                    break;
                case Cursor.NW_RESIZE_CURSOR:
                    width -= dx;
                    height -= dy;
                    break;
                }

                //ensure new size in bounds
                final Dimension minSize = getMinimumSize();
                final Dimension maxSize = getMaximumSize();
                if (width < minSize.width) {
                    dx += (width - minSize.width);
                    width = minSize.width;
                }
                else if (width > maxSize.width) {
                    dx -= (width - maxSize.width);
                    width = maxSize.width;
                }
                if (height < minSize.height) {
                    dy += (height - minSize.height);
                    height = minSize.height;
                }
                else if (height > maxSize.height) {
                    dy -= (height - maxSize.height);
                    height = maxSize.height;
                }

                //determine new location based on resize direction
                int x = locBeforeMove.x;
                int y = locBeforeMove.y;
                switch (resizeCursor) {
                case Cursor.W_RESIZE_CURSOR:
                case Cursor.SW_RESIZE_CURSOR:
                    x += dx;
                    break;
                case Cursor.N_RESIZE_CURSOR:
                case Cursor.NE_RESIZE_CURSOR:
                    y += dy;
                    break;
                case Cursor.NW_RESIZE_CURSOR:
                    x += dx;
                    y += dy;
                    break;
                }

                //set bounds based on new size and location
                setBounds(x, y, width, height);
            }
        });
    }
}
