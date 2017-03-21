package org.allenai.scienceparse.pdfapi;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 *
 */
@Builder
@Data
public class PDFPage {
  public final List<PDFLine> lines;
  public final int pageNumber;
  public final int pageWidth;
  public final int pageHeight;
  // TODO(aria42) graphics/objects
}
