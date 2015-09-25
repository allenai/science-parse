package org.allenai.scienceparse.pdfapi;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PDFDoc {
    public List<PDFPage> pages;
    public PDFMetadata meta;
    /**
     * Index in the lines of the first page which is the stop (one beyond the last)
     * line that makes the header of the document (the title, authors, etc.)
     *
     * This is < 0 if we can't find an approriate header/main cut.
     */
    private final int headerStopLinePosition;

    public List<PDFLine> heuristicHeader() {
        return headerStopLinePosition >= 0 ?
            pages.get(0).lines.subList(0, headerStopLinePosition) :
            null;
    }
}
