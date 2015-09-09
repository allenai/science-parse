package org.allenai.scienceparse.pdfapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class
        PDFExtractorTest {

    @SneakyThrows
    private void testPDF(String id) {
        String jsonPath = id + ".extraction.json";
        String pdfPath = id + ".pdf";
        InputStream jsonInputStream = getClass().getResourceAsStream(jsonPath);
        InputStream pdfInputStream = getClass().getResourceAsStream(pdfPath);
        PDFExtractor.Options opts = PDFExtractor.Options.builder().useHeuristicTitle(true).build();
        PDFDoc doc = new PDFExtractor(opts).extractFromInputStream(pdfInputStream);
        List<List<?>> arr =  new ObjectMapper().readValue(jsonInputStream, List.class);
        for (List<?> elems : arr) {
            String type = (String) elems.get(0);
            Object expectedValue = elems.get(1);
            if (type.equalsIgnoreCase("title")) {
                String guessValue = doc.getMeta().getTitle();
                Assert.assertEquals(guessValue.trim(), expectedValue, String.format("Title error on %s", id));
            }
            if (type.equalsIgnoreCase("line")) {
                List<PDFLine> lines = doc.getPages().stream().flatMap(x -> x.getLines().stream()).collect(Collectors.toList());
                boolean matchedLine = lines.stream().anyMatch(l -> l.lineText().equals(expectedValue));
                Assert.assertTrue(matchedLine, String.format("Line-match error on %s for line: %s", id, expectedValue));
            }
            if (type.equalsIgnoreCase("year")) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(doc.getMeta().getCreateDate());
                int year = cal.get(Calendar.YEAR);
                Assert.assertEquals(year, expectedValue, String.format("Year error on %s", id));
            }
        }
    }

    private final static List<String> pdfKeys = Arrays.asList("/bagnell11", "/seung08", "/ding11", "/mooney05",
        "/roark13", "/dyer12", "/bohnet09", "/P14-1059", "/map-reduce", "/fader11", "/proto06",
        "/agarwal11", "/smola10", "/senellart10", "/zolotov04","/pedersen04", "/smith07",
        "/aimag10");

    @Test
    public void testPDFExtraction() throws Exception {
        pdfKeys.forEach(this::testPDF);
    }
    
    public void testPDFBenchmark() throws Exception {
        long numTitleBytes = 0L;
        for (int idx = 0; idx < 10; ++idx) {
            for (String pdfKey : pdfKeys) {
                InputStream pdfInputStream = PDFExtractorTest.class.getResourceAsStream(pdfKey + ".pdf");
                PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
                numTitleBytes += doc.getMeta().hashCode();
            }
        }
        long start = System.currentTimeMillis();
        long testNum = 0L;
        for (int idx = 0; idx < 10; ++idx) {
            for (String pdfKey : pdfKeys) {
                InputStream pdfInputStream = PDFExtractorTest.class.getResourceAsStream(pdfKey + ".pdf");
                PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
                numTitleBytes += doc.getMeta().hashCode();
                testNum++;
            }
        }
        long stop = System.currentTimeMillis();
        int numPasses = pdfKeys.size() * 20;
        log.info("Time {} on {}, avg: {}ms\n", stop - start, numPasses, (stop - start) / testNum);
        log.info("Just to ensure no compiler tricks: " + numTitleBytes);
    }

    private static String processTitle(String t) {
        // case fold and remove lead/trail space
        t = t.trim().toLowerCase();
        // strip accents and unicode changes
        t = Normalizer.normalize(t, Normalizer.Form.NFKD);
        // kill non-character letters
        // kill xml
        t = t.replaceAll("\\&.*?\\;","");
        // kill non-letter chars
        //t = t.replaceAll("\\W","");
        return t.replaceAll("\\s+"," ");
    }

    @SneakyThrows
    public static void main(String[] args) {
        // args[0] should be directory with eval PDFs
        File dir = new File(args[0]);
        // args[1] should be src/test/resources/id-titles.txt
        // tab-separared: content-sha, expected title
        BufferedReader keyReader = new BufferedReader(new FileReader(args[1]));
        Iterator<String> keyIt = keyReader.lines().iterator();
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int numEvents = 0;
        while (keyIt.hasNext()) {
            String line = keyIt.next();
            String[] fields = line.split("\\t");
            String key = fields[0];
            String expectedTitle = fields[1];
            File pdfFile = new File(dir, key + ".pdf");
            // This PDF isn't part of evaluation PDFs
            if (!pdfFile.exists()) {
                continue;
            }
            // We know we need to evaluate on this
            numEvents ++;
            PDFExtractor.Options opts = PDFExtractor.Options.builder().useHeuristicTitle(false).build();
            PDFDoc doc = new PDFExtractor(opts).extractFromInputStream(new FileInputStream(pdfFile));
            String guessTitle = doc.getMeta().getTitle();
            if (guessTitle == null) {
                // Didn't guess but there is an answer
                fn ++;
                continue;
            }
            String guessTitleCollapsed = processTitle(guessTitle);
            String expectedTitleCollapsed = processTitle(expectedTitle);
            boolean equiv = expectedTitleCollapsed.equals(guessTitleCollapsed);
            if (equiv) {
                // we guessed and guess correctly
                tp++;
            } else {
                // we guessed and guess incorrectly
                fp++;
            }
            if (!equiv) {
                System.out.println("pdf: " + pdfFile.getName());
                System.out.println("expectedTitle: " + expectedTitle);
                System.out.println("guessTitle: " + guessTitle);
                System.out.println("");
                PDFExtractor extractor = new PDFExtractor(opts);
                extractor.DEBUG = true;
                extractor.extractFromInputStream(new FileInputStream(pdfFile));
            }
        }
        double precision = tp / ((double)(tp + fp));
        double recall = tp / ((double)(tp + fn));
        int guessNumEvents = tp + fp + fn;
        System.out.println("");
        System.out.println("Num events known, guessed: " + numEvents + ", " + guessNumEvents);
        System.out.println("Precision: " + precision);
        System.out.println("Recall: " + recall);
        double f1 = 2 * precision * recall / (precision + recall);
        System.out.println("F1: " + f1);
    }
}