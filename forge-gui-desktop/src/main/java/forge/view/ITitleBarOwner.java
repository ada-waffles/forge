package forge.view;

import java.awt.Image;

import javax.swing.JMenuBar;

public interface ITitleBarOwner {
    boolean isMinimized();

    void setMinimized(boolean b);

    boolean isMaximized();

    void setMaximized(boolean b);

    boolean isFullScreen();

    void setFullScreen(boolean b);

    /**
     * @return
     * whether to apply the restrictions of macOS fullscreen mode:
     * minimizing fullscreen windows is not allowed and the title bar cannot be hidden
     */
    boolean macosFullScreenRules();

    boolean getLockTitleBar();

    void setLockTitleBar(boolean b);

    int getWidth();

    void setJMenuBar(JMenuBar menuBar);

    String getTitle();

    Image getIconImage();
}
