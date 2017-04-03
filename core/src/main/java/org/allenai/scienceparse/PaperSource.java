package org.allenai.scienceparse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Encapsulates a way to get PDFs from paper ids.
 */
public abstract class PaperSource {
    abstract InputStream getPdf(String paperId) throws IOException;

    private static PaperSource defaultPaperSource = null;
    static synchronized PaperSource getDefault() {
        if(defaultPaperSource == null)
            defaultPaperSource = new RetryPaperSource(S2PaperSource$.MODULE$, 5);
        return defaultPaperSource;
    }
}
