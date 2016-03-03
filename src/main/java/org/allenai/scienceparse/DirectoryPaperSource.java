package org.allenai.scienceparse;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * A paper source that gets papers from a directory in the file system
 */
public class DirectoryPaperSource implements PaperSource {
    private final File dir;

    public DirectoryPaperSource(final File dir) {
        this.dir = dir;
    }

    @Override
    public InputStream getPdf(final String paperId) throws FileNotFoundException {
        final File file = new File(dir, paperId + ".pdf");
        return new BufferedInputStream(new FileInputStream(file));
    }
}

