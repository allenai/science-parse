package org.allenai.scienceparse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * A paper source that uses other paper sources, one after the other, to tro to locate a paper.
 */
public class FallbackPaperSource implements PaperSource {
  private final static Logger logger =
      LoggerFactory.getLogger(FallbackPaperSource.class);

  private final PaperSource[] sources;
  public FallbackPaperSource(final PaperSource... sources) {
    this.sources = sources;
  }

  @Override
  public InputStream getPdf(final String paperId) throws IOException {
    // Try all but the last source.
    for(int i = 0; i < sources.length - 1; ++i) {
      final PaperSource source = sources[i];
      try {
        return source.getPdf(paperId);
      } catch (final Exception e) {
        logger.info(
            "Getting paper {} from source {} failed, {} more sources to try",
            paperId,
            i,
            sources.length - i - 1);
      }
    }

    // Try the last source.
    final PaperSource source = sources[sources.length - 1];
    return source.getPdf(paperId);
  }
}
