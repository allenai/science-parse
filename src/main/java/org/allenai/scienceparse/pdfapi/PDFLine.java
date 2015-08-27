package org.allenai.scienceparse.pdfapi;

import com.gs.collections.api.list.primitive.IntList;
import com.gs.collections.impl.list.mutable.primitive.IntArrayList;
import lombok.Builder;
import lombok.Data;

import java.util.List;
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


    private IntStream projectCoord(int dim) {
        return tokens.stream().mapToInt(t -> t.bounds.get(dim));
    }

    /**
     * (0,0) origin bounds [x0,y0, x1, y1] for the entire line.
     * Should
     */
    public IntList bounds() {
        int x0 = projectCoord(0).min().getAsInt();
        int y0 = projectCoord(1).min().getAsInt();
        int x1 = projectCoord(2).max().getAsInt();
        int y1 = projectCoord(3).max().getAsInt();
        return IntArrayList.newListWith(x0, y0, x1, y1);
    }
}
