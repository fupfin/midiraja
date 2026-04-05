package com.fupfin.midiraja.ui;

import java.util.List;

public class CompositePanel implements Panel
{
    private final List<Panel> panels;
    private LayoutConstraints constraints = new LayoutConstraints(80, 0, false, false);
    private final int[] fixedHeights; // How to distribute heights. Wait, let's keep it simple.

    public CompositePanel(Panel... panels)
    {
        this.panels = List.of(panels);
        this.fixedHeights = new int[panels.length];
        // We assume Metadata needs 2 lines, Status takes the rest.
        // It's specific for Now Playing, so let's just make it NowPlayingPanel or hardcode the
        // distribution here.
    }

    public void setHeights(int... heights)
    {
        System.arraycopy(heights, 0, this.fixedHeights, 0,
                Math.min(heights.length, this.fixedHeights.length));
    }

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds)
    {
        this.constraints = bounds;
        // Distribute height based on fixedHeights
        int currentY = 0;
        for (int i = 0; i < panels.size(); i++)
        {
            int h = (i == panels.size() - 1)
                    ? Math.max(0, bounds.height() - currentY)
                    : fixedHeights[i];
            panels.get(i).onLayoutUpdated(new LayoutConstraints(bounds.width(), h,
                    bounds.showHeaders(), bounds.isHorizontal()));
            currentY += h;
        }
    }

    @Override
    public void onPlaybackStateChanged()
    {
        panels.forEach(Panel::onPlaybackStateChanged);
    }

    @Override
    public void onTick(long currentMicroseconds)
    {
        panels.forEach(p -> p.onTick(currentMicroseconds));
    }

    @Override
    public void onTempoChanged(float bpm)
    {
        panels.forEach(p -> p.onTempoChanged(bpm));
    }

    @Override
    public void onChannelActivity(int channel, int velocity)
    {
        panels.forEach(p -> p.onChannelActivity(channel, velocity));
    }

    @Override
    public void render(ScreenBuffer buffer)
    {
        if (constraints.height() <= 0)
            return;
        panels.forEach(p -> p.render(buffer));
    }
}
