package com.midiraja.ui;

/**
 * Encapsulates global UI constants, ANSI escape sequences, and magic characters
 * used throughout the terminal application to maintain visual consistency.
 */
public final class Theme {
    
    // --- ANSI Color Codes ---
    public static final String COLOR_RESET = "\033[0m";
    
    // Roland SC-55 Amber aesthetic (256 color code 215)
    public static final String COLOR_HIGHLIGHT = "\033[38;5;215m"; // Default: Amber
    
    // Classic Cyan (for highlights, active tracks, etc.)
    // public static final String COLOR_CYAN = "\033[1;36m"; // Replaced by semantic names
    
    // Warning Yellow (for PAUSED state)
    public static final String COLOR_YELLOW = "\033[1;33m";
    
    // Invert Colors (for top banner)
    public static final String FORMAT_INVERT = "\033[7m";
    
    // --- UI Magic Characters ---
    public static final String CHAR_BLOCK_FULL = "█";
    public static final String CHAR_BLOCK_EMPTY = "░";
    public static final String CHAR_ARROW_RIGHT = ">";
    
    // For Dashboard structure
    public static final String BORDER_HORIZONTAL = "-";
    public static final String BORDER_BOTTOM_HEAVY = "=";
    public static final String DECORATOR_TITLE_PREFIX = " ≡≡[ ";
    public static final String DECORATOR_TITLE_SUFFIX = " ]";
    public static final String DECORATOR_LINE = "≡";
    
    // Terminal Control Sequences
    public static final String TERM_CLEAR_TO_EOL = "\033[K";
    public static final String TERM_CLEAR_TO_END = "\033[J";
    public static final String TERM_CURSOR_HOME = "\033[H";
    public static final String TERM_CURSOR_UP = "\033[A";
    
    public static final String TERM_HIDE_CURSOR = "\033[?25l";
    public static final String TERM_SHOW_CURSOR = "\033[?25h";
    public static final String TERM_ALT_SCREEN_ENABLE = "\033[?1049h";
    public static final String TERM_ALT_SCREEN_DISABLE = "\033[?1049l";

    /** Disables all common mouse-tracking modes to prevent scroll-wheel events from
     *  leaking into the shell as cursor-key presses after the application exits. */
    public static final String TERM_MOUSE_DISABLE =
            "\033[?1000l"   // X10 mouse tracking off
          + "\033[?1002l"   // button-event tracking off
          + "\033[?1003l"   // any-event tracking off
          + "\033[?1006l"   // SGR mouse extension off
          + "\033[?1007l"   // alternate scroll mode off
          + "\033[?1015l";  // URXVT mouse extension off

    private Theme() {} // Prevent instantiation
}
