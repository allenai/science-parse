package org.allenai.scienceparse;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.allenai.pdfbox.io.IOUtils;
import org.testng.annotations.Test;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

@Test(groups = {"integration"})
@Slf4j
public class HeaderIntegrationTest {
    static final int kSampledPapers = 100;
    static final String kTestDirectory = "./testdata/dblp";

    public static class Result {
        int authorHits;
        int authorInvalid;
        boolean titleMatch;
        String title;
        int totalAuthors;
        boolean titleMissing;
    }

    public static File downloadPDF(String urlStr, String targetDir) {
        new File(targetDir).mkdirs();
        try {
            URL url = new URL(urlStr);
            String fileName = new File(url.getFile()).getName();
            File outputFile = new File(targetDir, fileName);

            if (outputFile.exists()) {
                return outputFile;
            }

            log.info("Fetching: {} -> {}", urlStr, fileName);
            InputStream is = url.openStream();
            byte[] bytes = IOUtils.toByteArray(is);
            FileOutputStream fOs = new FileOutputStream(outputFile);
            fOs.write(bytes);
            fOs.close();

            return outputFile;
        } catch (IOException e) {
            log.warn("Failed to download PDF {}, {}", urlStr, e);
            return null;
        }
    }

    public static void downloadAllPapers(ParserGroundTruth pgt) throws Exception {
        new File(kTestDirectory).mkdirs();
        for (ParserGroundTruth.Paper paper : pgt.papers) {
            downloadPDF(paper.url, kTestDirectory);
        }
    }

    public Parser trainParser(ParserGroundTruth pgt) throws Exception {
        ParserGroundTruth subset = new ParserGroundTruth(pgt.papers.subList(0, 100));
        Parser.ParseOpts opts = new Parser.ParseOpts();

        final File tempModelFile = File.createTempFile("science-parse-temp-model.", ".dat");
        tempModelFile.deleteOnExit();
        opts.modelFile = tempModelFile.getPath();
        opts.headerMax = Parser.MAXHEADERWORDS;
        opts.iterations = 10;
        opts.threads = 4;
        opts.backgroundSamples = 400;
        opts.backgroundDirectory = "testdata/background";
        opts.gazetteerFile = null;
        opts.trainFraction = 0.9;
        opts.checkAuthors = true;
        opts.minYear = 2008;

        log.info("Training CRF with {} papers", subset.papers.size());
        Parser.trainParser(null, subset, "testdata/papers", opts, null);

        final Parser result;
        try(
          val modelStream = new FileInputStream(tempModelFile);
          val gazetteerStream = getClass().getResourceAsStream("/referencesGroundTruth.json")
        ){
          result = new Parser(modelStream, gazetteerStream);
        }
        tempModelFile.delete();
        return result;
    }

    public Parser loadProductionParser() throws Exception {
        try(
          val modelStream = new FileInputStream("models/model-production_12_1_15.dat");
          val gazetteerStream = getClass().getResourceAsStream("/referencesGroundTruth.json")
        ){
          return new Parser(modelStream, gazetteerStream);
        }
    }

    public static HashSet<String> authorSet(Iterable<String> authors) {
        HashSet<String> result = new HashSet<String>();
        for (String author : authors) {
            result.add(Parser.lastName(author));
        }
        return result;
    }

    public static Result testPaper(Parser parser, ParserGroundTruth pgt, File pdfFile) {
        ExtractedMetadata metadata;

        // TODO -- why is the paper id a substring of parsergroundtruth?
        String key = pdfFile.getName().split("[.]")[0];
        ParserGroundTruth.Paper paper = pgt.forKey(key);

        try {
            metadata = parser.doParse(
                    new FileInputStream(pdfFile),
                    Parser.MAXHEADERWORDS);
        } catch (Exception e) {
            log.info("Failed to parse or extract from {}.  Skipping.", paper.url);
            return null;
        } catch (NoClassDefFoundError e) {
            log.info("Ignoring encrypted PDF {}", pdfFile.getName());
            return null;
        }

        HashSet<String> golden = authorSet(Arrays.asList(paper.authors));
        HashSet<String> extracted = authorSet(metadata.authors);

        int hits = 0;
        int invalid = 0;
        for (String name : golden) {
            if (extracted.contains(name)) {
                hits += 1;
            }
        }
        for (String name : extracted) {
            if (!golden.contains(name)) {
                log.info("Bad author {}: {} ", name,
                        String.join(",", golden.toArray(new String[]{}))
                );
                invalid += 1;
            }
        }
        Result res = new Result();
        res.totalAuthors = golden.size();
        res.authorHits = hits;
        res.authorInvalid = invalid;
        res.title = paper.title;

        if (metadata.title == null) {
            res.titleMatch = false;
            res.titleMissing = true;
        } else {
            res.titleMatch = Parser.processTitle(paper.title)
                    .equals(Parser.processTitle(metadata.title));
        }


        if (res.authorInvalid > 0 || !res.titleMatch) {
            metadata.authors.sort((String a, String b) -> a.compareTo(b));
            Arrays.sort(paper.authors);
            log.info("Failed match for paper: {}.", pdfFile.getAbsolutePath());
            log.info("Titles: GOLD:\n{} OURS:\n{}", paper.title, metadata.title);
            for (int i = 0; i < Math.max(paper.authors.length, metadata.authors.size()); ++i) {
                String goldAuthor = null;
                String metaAuthor = null;
                if (i < paper.authors.length) { goldAuthor = paper.authors[i]; }
                if (i < metadata.authors.size()) { metaAuthor = metadata.authors.get(i); }
                log.info("Author: ({}) ({})", goldAuthor, metaAuthor);
            }
        }

        return res;
    }

    public void testAuthorAndTitleExtraction() throws Exception {
        ParserGroundTruth pgt = new ParserGroundTruth(
                HeaderIntegrationTest.class.getResourceAsStream("/referencesGroundTruth.json"));

        // TODO (build and train a classifier at test time).
        //        Parser parser = trainParser(pgt);
        Parser parser = loadProductionParser();

        ArrayList<ParserGroundTruth.Paper> sampledPapers = new ArrayList<>();

        for (int i = 0; i < pgt.papers.size(); i += pgt.papers.size() / kSampledPapers) {
            sampledPapers.add(pgt.papers.get(i));
        }

        long startTime = System.currentTimeMillis();
        ArrayList<Result> results = sampledPapers
                .stream()
                .parallel()
                .map(p -> downloadPDF(p.url, kTestDirectory))
                .filter(f -> f != null)
                .map(f -> testPaper(parser, pgt, f))
                .filter(f -> f != null)
                .collect(Collectors.toCollection(ArrayList::new));

        // Gahh I wish I had a dataframe library...
        int totalHits = 0, totalInvalid = 0, totalAuthors = 0, titleMatches = 0, titleMissing = 0;
        for (Result res : results) {
            totalHits += res.authorHits;
            totalInvalid += res.authorInvalid;
            totalAuthors += res.totalAuthors;
            if (res.titleMatch) {
                titleMatches += 1;
            }
            if (res.titleMissing) {
                titleMissing += 1;
            }
        }

        long finishTime = System.currentTimeMillis();
        double elapsed = (finishTime - startTime) / 1000.0;
        log.info("Testing complete.  {} papers processed in {} seconds; {} papers/sec ",
                results.size(), elapsed, results.size() / elapsed);

        log.info("Authors: {} (Match: {} Invalid: {} Total {})",
                totalHits / (double)totalAuthors, totalHits, totalInvalid, totalAuthors);
        log.info("Titles:  {} (Match: {} Missing: {} Total {})",
                titleMatches / (double)results.size(), titleMatches, titleMissing, results.size());
    }
}
