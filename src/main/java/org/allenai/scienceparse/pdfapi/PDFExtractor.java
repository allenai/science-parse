package org.allenai.scienceparse.pdfapi;

import com.gs.collections.api.list.primitive.FloatList;
import com.gs.collections.impl.list.mutable.primitive.FloatArrayList;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.DateConverter;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;
import java.util.function.ToDoubleFunction;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PDFExtractor {

    @Builder
    public static class Options {
        public boolean useHeuristicTitle = false;
    }

    private final Options opts;

    public PDFExtractor(Options opts) {
        this.opts = opts;
    }

    public PDFExtractor() {
        this.opts = Options.builder().build();
    }

    @Data(staticConstructor = "of")
    private final static class RawChunk {
        // The PDFBox class doesn't get exposed outside of this class
        public final List<TextPosition> textPositions;

        public PDFToken toPDFToken() {
            val builder = PDFToken.builder();
            String tokenText = textPositions.stream().map(TextPosition::getUnicode).collect(Collectors.joining(""));
            // separate ligands
            tokenText = Normalizer.normalize(tokenText, Normalizer.Form.NFKC);
            builder.token(tokenText);
            // HACK(aria42) assumes left-to-right text
            TextPosition firstTP = textPositions.get(0);
            PDFont pdFont = firstTP.getFont();
            val desc = pdFont.getFontDescriptor();
            String fontFamily = desc == null ? PDFFontMetrics.UNKNWON_FONT_FAMILY : desc.getFontName();
            float ptSize = firstTP.getFontSizeInPt();
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
            List<TextPosition> curPositions = new ArrayList<>();
            List<PDFToken> tokens = new ArrayList<>();
            for (int idx = 0; idx < textPositions.size(); idx++) {
                TextPosition tp = textPositions.get(idx);
                if (tp.getUnicode().trim().isEmpty()) {
                    List<TextPosition> tokenPositions = new ArrayList<>(curPositions);
                    if (tokenPositions.size() > 0) {
                        tokens.add(RawChunk.of(tokenPositions).toPDFToken());
                    }
                    curPositions.clear();
                } else {
                    curPositions.add(tp);
                }
            }
            if (!curPositions.isEmpty()) {
                tokens.add(RawChunk.of(new ArrayList<>(curPositions)).toPDFToken());
            }
            for (PDFToken token : tokens) {
                updateFromToken(token);
            }

        }

        private void updateFromToken(PDFToken token) {
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

    private boolean badPDFTitleFast(String title) {
        if (title == null) {
            return true;
        }
        // Ending with file extension is what Microsoft Word tends to do
        if (
            title.endsWith(".pdf") || title.endsWith(".doc") ||
            // Ellipsis are bad, since title is abbreviated
            title.endsWith("...") ||
            // Some conferences embed this in start of title
            // HACK(aria42) English-specific and conference-structure specific
            title.trim().toLowerCase().startsWith("proceedings of"))
        {
            return true;
        }
        // Check words are capitalized
        String[] words = title.split("\\s+");
        boolean hasCapitalWord = Stream.of(words)
            .filter(w -> !w.isEmpty())
            .anyMatch(w -> Character.isUpperCase(w.charAt(0)));
        if (!hasCapitalWord) {
            return true;
        }
        return false;
    }

    private boolean badPDFTitle(PDFPage firstPage, String title) {
        if (badPDFTitleFast(title)) {
            return true;
        }
        Optional<PDFLine> matchLine = firstPage.lines.stream().filter(l -> {
            String lineText = l.lineText();
            return lineText.startsWith(title) || title.startsWith(lineText);
        }).findFirst();
        return !matchLine.isPresent();
    }

    private Date toDate(String cosVal) {
        if (cosVal == null) {
            return null;
        }
        String strippedDate = cosVal.replace("^D:", "");
        val cal = DateConverter.toCalendar(strippedDate);
        return cal == null ? null : cal.getTime();
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
        String createDate = info.getCustomMetadataValue(COSName.CREATION_DATE.getName());
        if (createDate != null) {
            meta.createDate(toDate(createDate));
        } else {
            // last ditch attempt to read date from non-standard meta
            OptionalInt guessYear = Stream.of("Date", "Created")
                .map(info::getCustomMetadataValue)
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
        String lastModDate = info.getCustomMetadataValue(COSName.CREATION_DATE.getName());
        if (lastModDate != null) {
            meta.lastModifiedDate(toDate(lastModDate));
        }
        val stripper = new PDFCaptureTextStripper();
        // SIDE-EFFECT pages ivar in stripper is populated
        stripper.getText(pdfBoxDoc);
        String title = info.getTitle();
        // kill bad title
        if (badPDFTitle(stripper.pages.get(0), title)) {
            title = null;
        }
        // Title heuristic
        if (opts.useHeuristicTitle && title == null) {
            String guessTitle = getHeuristicTitle(stripper);
            if (!badPDFTitleFast(guessTitle)) {
                title = guessTitle;
            }
        }
        meta.title(title);
        pdfBoxDoc.close();
        return PDFDoc.builder()
            .pages(stripper.pages)
            .meta(meta.build())
            .build();
    }

    private static double relDiff(double a, double b) {
        return Math.abs(a-b)/Math.min(Math.abs(a), Math.abs(b));
    }

    public boolean DEBUG = false;

    private String getHeuristicTitle(PDFCaptureTextStripper stripper) {
        PDFPage firstPage = stripper.pages.get(0);
        ToDoubleFunction<PDFLine> lineFontSize =
            //line -> line.height();
            line -> line.getTokens().stream().mapToDouble(t -> t.getFontMetrics().getPtSize()).average().getAsDouble();
        double largestSize = firstPage.getLines().stream()
            .filter(l -> !l.getTokens().isEmpty())
            .mapToDouble(lineFontSize::applyAsDouble)
            .max().getAsDouble();
        int startIdx = IntStream.range(0, firstPage.lines.size())
            //.filter(idx -> relDiff(lineFontSize.applyAsDouble(firstPage.lines.get(idx)), largestSize) < 0.01)
            .filter(idx -> lineFontSize.applyAsDouble(firstPage.lines.get(idx)) == largestSize)
            .findFirst().getAsInt();
        int stopIdx = IntStream.range(startIdx+1, firstPage.lines.size())
            //.filter(idx -> relDiff(lineFontSize.applyAsDouble(firstPage.lines.get(idx)),largestSize) >= 0.05)
            .filter(idx -> lineFontSize.applyAsDouble(firstPage.lines.get(idx)) < largestSize)
            .findFirst()
            .orElse(firstPage.lines.size() - 1);
        if (startIdx == stopIdx) {
            return null;
        }
        if (DEBUG) {
            System.out.println("HERE");
        }
        double lastYDiff = Double.NaN;
        List<PDFLine> titleLines = firstPage.lines.subList(startIdx, stopIdx);
        if (titleLines.size() == 1) {
            return titleLines.get(0).lineText();
        }
        PDFLine firstLine = titleLines.get(0);
        // If the line is to far down the first page, unlikely to be title
        float fractionDownPage = firstLine.bounds().get(1) / firstPage.getPageHeight();
        if (fractionDownPage > 0.66 || startIdx > 5) {
            return null;
        }
        for (int idx=0; idx+1 < titleLines.size(); ++idx) {
            PDFLine line = titleLines.get(idx);
            PDFLine nextLine = titleLines.get(idx+1);
            double yDiff = nextLine.bounds().get(1) - line.bounds().get(3);
            double yDiffNormed = yDiff / line.height();
            if (yDiffNormed > 1.5 || (idx > 0 && relDiff(yDiff, lastYDiff) > 0.1)) {
                titleLines = titleLines.subList(0, idx+1);
                break;
            }
            lastYDiff = yDiff;
        }
        return titleLines.stream().map(PDFLine::lineText).collect(Collectors.joining(" "));
    }

}
