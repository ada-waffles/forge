package forge.util;

/**
 * Utilities for detecting the current operating system
 */
public final class OSUtil {
    private OSUtil() {}

    private static OS detectedOS = null;

    public static OS detectOS() {
        if (detectedOS != null) return detectedOS;

        final String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            detectedOS = OS.WINDOWS;
        }
        else if (osName.contains("mac")) {
            detectedOS = OS.MAC_OS;
        }
        else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            detectedOS = OS.LINUX;
        }
        else {
            detectedOS = OS.OTHER;
        }

        return detectedOS;
    }

    public enum OS {
        WINDOWS,
        MAC_OS,
        LINUX,
        OTHER
    }
}
