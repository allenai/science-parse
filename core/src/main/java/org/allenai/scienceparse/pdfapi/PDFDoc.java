package org.allenai.scienceparse.pdfapi;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PDFDoc {
  /**
   * Index in the lines of the first page which is the stop (one beyond the last)
   * line that makes the header of the document (the title, authors, etc.)
   * <p>
   * This is < 0 if we can't find an appropriate header/main cut.
   */
  public List<PDFPage> pages;
  public PDFMetadata meta;
}
