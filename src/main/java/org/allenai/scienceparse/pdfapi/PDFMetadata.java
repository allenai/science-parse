package org.allenai.scienceparse.pdfapi;

import jdk.nashorn.internal.runtime.options.Option;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Immutable class representing information obtained from scanning for PDF
 * meta-data. Many pdf creation programs (like pdflatex) will actuallly output
 * information like these fields which substantially aids downstream extraction.
 */
@Builder
@Data
public class PDFMetadata {
    public final String title;
    public final List<String> authors;
    public final List<String> keywords;
    public final Date createDate;
    public final Date lastModifiedDate;
}
