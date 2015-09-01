package org.allenai.scienceparse.pdfapi;

import com.gs.collections.api.list.primitive.FloatList;
import com.gs.collections.impl.list.mutable.primitive.FloatArrayList;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.ToDoubleFunction;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PDFExtractor {

    @Data(staticConstructor = "of")
    private final static class RawChunk {
        public final String text;
        // The PDFBox class doesn't get exposed outside of this class
        public final List<TextPosition> textPositions;

        public PDFToken toPDFToken() {
            val builder = PDFToken.builder();
            builder.token(text.trim());
            // HACK(aria42) assumes left-to-right text
            TextPosition firstTP = textPositions.get(0);
            PDFont pdFont = firstTP.getFont();
            String fontFamily = pdFont.getBaseFont();
            if (fontFamily == null) {
                fontFamily = PDFFontMetrics.UNKNWON_FONT_FAMILY;
            }
            float ptSize = firstTP.getFontSize();
            val fontMetrics = PDFFontMetrics.of(fontFamily, ptSize, firstTP.getWidthOfSpace());
            builder.fontMetrics(fontMetrics);
            float minX = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (TextPosition tp : textPositions) {
                float x0 = tp.getX();
                if (x0 < minX) {
                    minX = x0;
                }
                float x1 = x0 + tp.getWidth();
                if (x1 > maxX) {
                    maxX = x1;
                }
                float y0 = tp.getY();
                if (y0 < minY) {
                    minY = y0;
                }
                float y1 = y0 + tp.getHeight();
                if (y1 > maxY) {
                    maxY = y1;
                }
            }
            FloatList bounds = FloatArrayList.newListWith(minX, minY, maxX, maxY);
            builder.bounds(bounds);
            return builder.build();
        }
    }


    private class PDFCaptureTextStripper extends PDFTextStripper {

        private List<PDFPage> pages = new ArrayList<>();
        private List<PDFLine> curLines;
        private List<PDFToken> curLineTokens;
        private PDFToken lastToken;

        // Mandatory for sub-classes
        public PDFCaptureTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            // Build current token and decide if on the same line as previous token or starts a new line
            PDFToken token = RawChunk.of(text, textPositions).toPDFToken();
            if (token.token.trim().isEmpty()) {
                return;
            }
            if (curLineTokens.isEmpty()) {
                curLineTokens.add(token);
            } else {
                double curYBottom = token.bounds.get(3);
                boolean yOffsetOverlap = curYBottom <= lastToken.bounds.get(3)
                    || curYBottom >= lastToken.bounds.get(1);
                float spaceWidth = Math.max(token.getFontMetrics().getSpaceWidth(), token.getFontMetrics().ptSize);
                float observedWidth = token.bounds.get(0) - lastToken.bounds.get(2);
                boolean withinSpace = observedWidth > 0 && observedWidth < 4 * spaceWidth;
                if (yOffsetOverlap && withinSpace) {
                    curLineTokens.add(token);
                } else {
                    curLines.add(toLine(curLineTokens));
                    curLineTokens.clear();
                    curLineTokens.add(token);
                }
            }
            lastToken = token;
        }


        @Override
        protected void startPage(PDPage page) {
            curLines = new ArrayList<>();
            curLineTokens = new ArrayList<>();
        }

        private PDFLine toLine(List<PDFToken> tokens) {
            // trigger copy of the list to defend against mutation
            return PDFLine.builder().tokens(new ArrayList<>(tokens)).build();
        }

        @Override
        protected void endPage(PDPage pdfboxPage) {
            if (!curLineTokens.isEmpty()) {
                curLines.add(toLine(curLineTokens));
            }
            PDRectangle pageRect = pdfboxPage.getMediaBox() == null ?
                pdfboxPage.getArtBox()  :
                pdfboxPage.getMediaBox();
            val page = PDFPage.builder()
                .lines(new ArrayList<>(curLines))
                .pageNumber(pages.size())
                .pageWidth((int) pageRect.getWidth())
                .pageHeight((int) pageRect.getHeight())
                .build();
            pages.add(page);
        }
    }

    private static List<String> guessKeywordList(String listStr) {
        return listStr != null && listStr.length() > 0
            ? Arrays.asList(listStr.split(","))
            : Collections.emptyList();
    }

    private static List<String> guessAuthorList(String listStr) {
        return listStr != null && listStr.length() > 0
            ? Arrays.asList(listStr.split(","))
            : Collections.emptyList();
    }

    private boolean badPDFTitle(PDFPage firstPage, String title) {
        if (// Ending with file extension is what Microsoft Word tends to do
            title.endsWith(".pdf") ||
            title.endsWith(".doc") ||
            // Ellipsis are bad
            title.endsWith("...") ||
            // Some conferences embed this in start of title
            title.toLowerCase().startsWith("Proceedings of"))
        {
            return true;
        }
        Optional<PDFLine> matchLine = firstPage.lines.stream().filter(l -> {
            String lineText = l.lineText();
            return lineText.startsWith(title) || title.startsWith(lineText);
        }).findFirst();
        return !matchLine.isPresent();
    }


    @SneakyThrows
    public PDFDoc extractFromInputStream(InputStream is) {
        PDDocument pdfBoxDoc = PDDocument.load(is);
        val info = pdfBoxDoc.getDocumentInformation();
        List<String> keywords = guessKeywordList(info.getKeywords());
        List<String> authors = guessAuthorList(info.getAuthor());
        val meta = PDFMetadata.builder()
            .title(info.getTitle() != null ? info.getTitle().trim() : null)
            .keywords(keywords)
            .authors(authors);
        val createDate = info.getCreationDate();
        if (createDate != null) {
            meta.createDate(createDate.getTime());
        } else {
            // last ditch attempt to read date from non-standard meta
            OptionalInt guessYear = Stream.of("Date", "Created")
                .map(k -> info.getCustomMetadataValue(k))
                .filter(d -> d != null && d.matches("\\d\\d\\d\\d"))
                .mapToInt(Integer::parseInt)
                .findFirst();
            if (guessYear.isPresent()) {
                Calendar calendar = Calendar.getInstance();
                calendar.clear();
                calendar.set(Calendar.YEAR, guessYear.getAsInt());
                meta.createDate(calendar.getTime());
            }
        }
        val lastModDate = info.getModificationDate();
        if (lastModDate != null) {
            meta.lastModifiedDate(lastModDate.getTime());
        }
        val stripper = new PDFCaptureTextStripper();
        // SIDE-EFFECT pages ivar in stripper is populated
        stripper.getText(pdfBoxDoc);
        // Title heuristic
        if (info.getTitle() == null || badPDFTitle(stripper.pages.get(0), info.getTitle())) {
            String guessTitle = getHeuristicTitle(stripper);
            if (guessTitle != null) {
                meta.title(guessTitle.trim());
            }
        }
        pdfBoxDoc.close();
        return PDFDoc.builder()
            .pages(stripper.pages)
            .meta(meta.build())
            .build();
    }

    private static String getHeuristicTitle(PDFCaptureTextStripper stripper) {
        PDFPage firstPage = stripper.pages.get(0);
        ToDoubleFunction<PDFLine> lineFontSize = line -> line.getTokens().get(0).getFontMetrics().getPtSize();
        double largestSize = firstPage.getLines().stream()
            .filter(l -> !l.getTokens().isEmpty())
            .mapToDouble(lineFontSize::applyAsDouble)
            .max().getAsDouble();
        int startIdx = IntStream.range(0, firstPage.lines.size())
            .filter(idx -> lineFontSize.applyAsDouble(firstPage.lines.get(idx)) == largestSize)
            .findFirst().getAsInt();
        int stopIdx = IntStream.range(startIdx+1, firstPage.lines.size())
            .filter(idx -> lineFontSize.applyAsDouble(firstPage.lines.get(idx)) < largestSize)
            .findFirst()
            .orElse(firstPage.lines.size() - 1);
        if (startIdx == stopIdx) {
            return null;
        }
        List<PDFLine> titleLines = firstPage.lines.subList(startIdx, stopIdx);
        for (int idx=0; idx+1 < titleLines.size(); ++idx) {
            PDFLine line = titleLines.get(idx);
            PDFLine nextLine = titleLines.get(idx+1);
            double yDiff = nextLine.bounds().get(1) - line.bounds().get(3);
            if (yDiff > line.height()) {
                titleLines = titleLines.subList(0, idx+1);
                break;
            }
        }
        return titleLines.stream().map(PDFLine::lineText).collect(Collectors.joining(" "));
    }

}
