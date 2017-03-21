package org.allenai.scienceparse.pdfapi;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class PdfDocExtractionResult {
  public final PDFDoc document;
  public final boolean highPrecision;
}
