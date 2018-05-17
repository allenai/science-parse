package org.allenai.scienceparse.pdfapi;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Wither;

import java.util.ArrayList;
import java.util.List;

@Builder
@Data
public class PDFPage {
  @Wither public final List<PDFLine> lines;
  public final int pageNumber;
  public final int pageWidth;
  public final int pageHeight;

  public PDFPage withoutSuperscripts() {
    final List<PDFLine> newLines = new ArrayList<>(lines.size());
    for(PDFLine line : lines) {
      final PDFLine newLine = line.withoutSuperscripts();
      if(!newLine.tokens.isEmpty())
        newLines.add(newLine);
    }
    return this.withLines(newLines);
  }
}
