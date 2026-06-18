package kr.co.voxelient.render;

import java.util.Set;

/**
 * Stores which section faces are connected through transparent space.
 */
public final class SectionVisibility {
    private static final int FACE_COUNT = SectionFace.values().length;
    private long bits;

    public static SectionVisibility allVisible() {
        SectionVisibility visibility = new SectionVisibility();
        visibility.setAllVisible(true);
        return visibility;
    }

    public void setAllVisible(boolean visible) {
        bits = visible ? -1L : 0L;
    }

    public void setManyVisible(Set<SectionFace> faces) {
        if (faces == null || faces.isEmpty()) {
            return;
        }

        for (SectionFace from : faces) {
            for (SectionFace to : faces) {
                setVisible(from, to, true);
            }
        }
    }

    public void setVisible(SectionFace from, SectionFace to, boolean visible) {
        setBit(index(from, to), visible);
        setBit(index(to, from), visible);
    }

    public boolean isVisible(SectionFace from, SectionFace to) {
        return (bits & (1L << index(from, to))) != 0L;
    }

    private int index(SectionFace from, SectionFace to) {
        return from.ordinal() + to.ordinal() * FACE_COUNT;
    }

    private void setBit(int index, boolean visible) {
        if (visible) {
            bits |= 1L << index;
        } else {
            bits &= ~(1L << index);
        }
    }
}
