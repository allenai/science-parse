package org.allenai.scienceparse;

import lombok.Data;
import lombok.experimental.Wither;

@Data
public class CitationRecord {
  public final int referenceID;

  @Wither
  public final String context;
  public final int startOffset;
  public final int endOffset;

  CitationRecord withConvertedSuperscriptTags() {
    final String newContext = context.
        replace('⍐', '(').
        replace('⍗', ')');
    return this.withContext(newContext);
  }
}
