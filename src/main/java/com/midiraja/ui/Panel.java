/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;

/**
 * Represents a modular, responsive UI component that can calculate its desired height
 * based on available space and render its state into a StringBuilder.
 */
public interface Panel
{
    /**
     * Determines the optimal height (in rows) this panel needs to render properly,
     * given the maximum available height. The panel may choose to shrink or collapse
     * if the available height is constrained.
     *
     * @param availableHeight The maximum number of rows currently available for this panel.
     * @return The number of rows this panel will actually consume.
     */
    int calculateHeight(int availableHeight);

    /**
     * Renders the panel's content based on the engine's current state.
     * The rendered content MUST exactly match the allocated height (number of 
 characters).
     *
     * @param sb The StringBuilder to append the rendered ANSI/text content to.
     * @param allocatedWidth The fixed width (columns) the panel must adhere to.
     * @param allocatedHeight The height (rows) this panel promised to consume in calculateHeight.
     * @param engine The playback engine providing the state.
     */
    void render(StringBuilder sb, int allocatedWidth, int allocatedHeight, PlaybackEngine engine);

    /**
     * Utility method to truncate long strings to fit the terminal width without wrapping.
     */
    default String truncate(String text, int maxLength)
    {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}