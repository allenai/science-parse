package org.allenai.scienceparse.pdfapi;

import com.gs.collections.api.list.primitive.FloatList;
import com.gs.collections.api.list.primitive.IntList;
import com.gs.collections.impl.list.mutable.primitive.FloatArrayList;
import com.gs.collections.impl.list.mutable.primitive.IntArrayList;
import lombok.Builder;
import lombok.Data;
import lombok.val;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 * Immutable value class representing a single contiguous line of a PDF. A contiguous line means
 * a sequence of tokens/glyphs which are intended to be read sequentially. For instance, a two column
 * paper might have two lines at the same y-position.
 */
@Builder
@Data
public class PDFLine {
    public List<PDFToken> tokens;

    private DoubleStream projectCoord(int dim) {
        return tokens.stream().mapToDouble(t -> t.bounds.get(dim));
    }

    /**
     * (0,0) origin bounds [x0,y0, x1, y1] for the entire line.
     * Should
     */
    public FloatList bounds() {
        float x0 = (float)projectCoord(0).min().getAsDouble();
        float y0 = (float)projectCoord(1).min().getAsDouble();
        float x1 = (float)projectCoord(2).max().getAsDouble();
        float y1 = (float)projectCoord(3).max().getAsDouble();
        return FloatArrayList.newListWith(x0, y0, x1, y1);
    }

    public float height() {
        val bs = bounds();
        return bs.get(3) - bs.get(0);
    }

    public String lineText() {
        return tokens.stream().map(PDFToken::getToken).collect(Collectors.joining(" "));
    }
}
