package org.allenai.scienceparse.pdfapi;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PDFDoc {
    public List<PDFPage> pages;
    public PDFMetadata meta;
}
