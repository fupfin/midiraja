/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

/**
 * Listener interface for receiving notifications when UI layout constraints change.
 */
public interface LayoutListener
{
    /**
     * Called when the layout manager recalculates the assigned bounds for this listener.
     *
     * @param bounds The new layout constraints.
     */
    void onLayoutUpdated(LayoutConstraints bounds);

    static record LayoutConstraints(int width, int height, boolean showHeaders, boolean isHorizontal) { }
}