package org.allenai.scienceparse.pdfapi;

import lombok.Data;
import lombok.val;

import java.util.concurrent.ConcurrentHashMap;

@Data
public class PDFFontMetrics {
    public final String name;
    public final float ptSize;
    public final float spaceWidth;

    private static final ConcurrentHashMap<String, PDFFontMetrics> canonical
        = new ConcurrentHashMap<>();

    /**
     * Ensures one font object per unique font name
     * @param name
     * @param ptSize
     * @param spaceWidth
     * @return
     */
    public static PDFFontMetrics of(String name, float ptSize, float spaceWidth) {
        val fontMetrics = new PDFFontMetrics(name, ptSize, spaceWidth);
        val curValue = canonical.putIfAbsent(name, fontMetrics);
        return curValue != null ? curValue : fontMetrics;
    }
}
