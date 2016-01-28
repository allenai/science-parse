package org.allenai.scienceparse;

import lombok.Data;

@Data
public class CitationRecord {
  public final int referenceID;

  public final String context;
  public final int startOffset;
  public final int endOffset;
}
