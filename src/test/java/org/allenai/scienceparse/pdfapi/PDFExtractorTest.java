package org.allenai.scienceparse.pdfapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.*;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class PDFExtractorTest {

    @SneakyThrows
    private void testPDF(String id) {
        String jsonPath = id + ".extraction.json";
        String pdfPath = id + ".pdf";
        InputStream jsonInputStream = getClass().getResourceAsStream(jsonPath);
        InputStream pdfInputStream = getClass().getResourceAsStream(pdfPath);
        PDFDoc doc = new PDFExtractor().extractFromInputStream(pdfInputStream);
        List<List<?>> arr =  new ObjectMapper().readValue(jsonInputStream, List.class);
        for (List<?> elems : arr) {
            String type = (String) elems.get(0);
            Object expectedValue = elems.get(1);
            if (type.equalsIgnoreCase("title")) {
                String guessValue = doc.getMeta().getTitle();
                Assert.assertEquals(guessValue, expectedValue, String.format("Title error on %s", id));
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
        "/roark13", "/dyer12", "/bohnet09", "/P14-1059", "/map-reduce", "/fader11", "/proto06", "/mono04",
        "/agarwal11", "/smola10", "/senellart10", "/zolotov04","/pedersen04", "/smith07",
        "/aimag10");

    @Test
    public void testPDFExtraction() throws Exception {
        pdfKeys.forEach(this::testPDF);
    }

    @Test
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
        // kill XML entities, then non-letter punct. characters, canonical space
        return t
         .toLowerCase()
         .replaceAll("\\&.*?\\;"," ")
         .replaceAll("\\p{Punct}", "")
         .replaceAll("\\W", "")
         .replaceAll("\\s+","");
    }

    @SneakyThrows
    public static void main(String[] args) {
        File dir = new File(args[0]);
        BufferedReader keyReader = new BufferedReader(new FileReader(args[1]));
        Iterator<String> keyIt = keyReader.lines().iterator();
        int tp = 0;
        int fp = 0;
        int fn = 0;
        while (keyIt.hasNext()) {
            String line = keyIt.next();
            String[] fields = line.split("\\t");
            String key = fields[0];
            String expectedTitle = fields[1];
            File pdfFile = new File(dir, key + ".pdf");
            if (!pdfFile.exists()) {
                continue;
            }
            PDFDoc doc = new PDFExtractor().extractFromInputStream(new FileInputStream(pdfFile));
            String guessTitle = doc.getMeta().getTitle();
            if (guessTitle == null) {
                fn ++;
                continue;
            }
            String guessTitleCollapsed = processTitle(guessTitle);
            String expectedTitleCollapsed = processTitle(expectedTitle);
            boolean equiv = expectedTitleCollapsed.equals(guessTitleCollapsed);
            if (equiv) {
                tp++;
            } else {
                fp++;
            }
            if (!equiv) {
                System.out.println("pdf: " + pdfFile.getName());
                System.out.println("expectedTitle: " + expectedTitle);
                System.out.println("guessTitle: " + guessTitle);
                System.out.println("");
            }
        }
        double precision = tp / ((double)(tp + fp));
        double recall = tp / ((double)(tp + fn));
        System.out.println("Precision: " + precision);
        System.out.println("Recall: " + recall);
        double f1 = 2 * precision * recall / (precision + recall);
        System.out.println("F1: " + f1);
    }
}