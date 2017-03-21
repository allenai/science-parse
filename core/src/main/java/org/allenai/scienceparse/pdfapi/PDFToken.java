package org.allenai.scienceparse.pdfapi;

import com.gs.collections.api.list.primitive.FloatList;
import lombok.Builder;
import lombok.Value;

@Builder
@Value
/**
 * Immutable value class
 */
public class PDFToken {
  public final String token;
  public final PDFFontMetrics fontMetrics;
  /**
   * List of ints [x0, y0, x1, y1] where [0,0] is upper left
   */
  public final FloatList bounds;
}
