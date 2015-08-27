package org.allenai.scienceparse.pdfapi;

import lombok.SneakyThrows;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class PDFExtractor {

    private class PDFCaptureTextStripper extends PDFTextStripper {
        // Mandatory for sub-classes
        public PDFCaptureTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            writeString(text);
        }
    }

    @SneakyThrows
    public PDFDoc extractFromInputStream(InputStream is) {
        PDDocument doc = PDDocument.load(is);
        // side-effect
        new PDFCaptureTextStripper().getText(doc);
        // TODO(aria42) actual implementation
        return null;
    }

    @SneakyThrows
    public static void main(String[] args) {
        new PDFExtractor().extractFromInputStream(new FileInputStream(args[0]));
    }

}
