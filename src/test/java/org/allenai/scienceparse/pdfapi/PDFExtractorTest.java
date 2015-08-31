package org.allenai.scienceparse.pdfapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.Calendar;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    @Test
    public void testPDFExtraction() throws Exception {
        Stream.of("/seung08", "/ding11", "/mooney05", "/roark13", "/dyer12","/bohnet09",
                  "/P14-1059","/map-reduce","/fader11", "/proto06","/mono04")
            .forEach(this::testPDF);
    }
}