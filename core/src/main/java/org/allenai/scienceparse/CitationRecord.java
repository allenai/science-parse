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

  CitationRecord(
      final int referenceID,
      final String context,
      final int startOffset,
      final int endOffset
  ) {
    // Looks like we have to have an explicit constructor because otherwise, Scala freaks out when
    // using this class.
    this.referenceID = referenceID;
    this.context = context;
    this.startOffset = startOffset;
    this.endOffset = endOffset;
  }

  CitationRecord withConvertedSuperscriptTags() {
    final String newContext = context.
        replace('⍐', '(').
        replace('⍗', ')');
    return this.withContext(newContext);
  }
}
