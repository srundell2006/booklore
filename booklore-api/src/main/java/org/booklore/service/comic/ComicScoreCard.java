package org.booklore.service.comic;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates {@link ComicSignal}s and clamps the running total to 0-100.
 *
 * <p>A signal worth 100 points (CBX extension, embedded ComicInfo.xml) is
 * treated as conclusive: {@link #isConclusive()} short-circuits the more
 * expensive analysis stages.
 */
public class ComicScoreCard {

    private static final int CONCLUSIVE_POINTS = 100;

    private final List<ComicSignal> signals = new ArrayList<>();
    private int raw = 0;
    private boolean conclusive = false;

    public void add(String code, int points, String detail) {
        signals.add(new ComicSignal(code, points, detail));
        raw += points;
        if (points >= CONCLUSIVE_POINTS) {
            conclusive = true;
        }
    }

    public boolean isConclusive() {
        return conclusive;
    }

    public int score() {
        return Math.max(0, Math.min(100, raw));
    }

    public List<ComicSignal> signals() {
        return List.copyOf(signals);
    }

    /** One reason per line, ready to persist into the candidate row. */
    public String reasons() {
        StringBuilder sb = new StringBuilder(256);
        for (ComicSignal s : signals) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }
}
