/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

/**
 * Represents a modular, responsive UI component that receives state updates via events and renders
 * its current internal representation.
 */
public interface Panel extends LayoutListener, PlaybackEventListener
{
    /**
     * Renders the panel's content into the provided StringBuilder. The panel should use its cached
     * layout constraints and state.
     *
     * @param buffer
     *            The ScreenBuffer to append the rendered ANSI/text content to.
     */
    void render(ScreenBuffer buffer);

    /**
     * Utility method to truncate long strings to fit the terminal width without wrapping.
     */
    default String truncate(String text, int maxLength)
    {
        if (text == null)
            return "";
        // Strip ANSI codes to calculate visible length
        String stripped = text.replaceAll("\\033\\[[;\\d]*m", "");
        if (stripped.length() <= maxLength)
            return text;

        // If we must truncate, it's safer to truncate the stripped version to avoid breaking ANSI
        // codes
        return stripped.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
