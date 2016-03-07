package org.allenai.scienceparse;

import junit.framework.Assert;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.allenai.pdfbox.io.IOUtils;
import org.testng.annotations.Test;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

@Test(groups = {"integration"})
@Slf4j
public class HeaderIntegrationTest {
    private final static PaperSource paperSource =
            new RetryPaperSource(ScholarBucketPaperSource.getInstance(), 5);

    static final int kSampledPapers = 100;

    public static class Result {
        int authorHits;
        int authorInvalid;
        boolean titleMatch;
        String title;
        int totalAuthors;
        boolean titleMissing;
    }

    public static HashSet<String> authorSet(Iterable<String> authors) {
        HashSet<String> result = new HashSet<String>();
        for (String author : authors) {
            result.add(Parser.lastName(author));
        }
        return result;
    }

    public static Result testPaper(
            final Parser parser,
            final ParserGroundTruth pgt,
            final String paperId
    ) {
        ExtractedMetadata metadata;

        ParserGroundTruth.Paper paper = pgt.forKey(paperId.substring(4));

        try {
            metadata = parser.doParse(
                    paperSource.getPdf(paperId),
                    Parser.MAXHEADERWORDS);
        } catch (Exception e) {
            log.info("Failed to parse or extract from {}.  Skipping.", paper.url);
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
            log.info("Failed match for paper {}.", paperId);
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
          Files.newInputStream(Parser.getDefaultGazetteer()));

        // TODO (build and train a classifier at test time).
        //        Parser parser = trainParser(pgt);
        Parser parser = new Parser();

        ArrayList<ParserGroundTruth.Paper> sampledPapers = new ArrayList<>();

        for (int i = 0; i < pgt.papers.size(); i += pgt.papers.size() / kSampledPapers) {
            sampledPapers.add(pgt.papers.get(i));
        }

        long startTime = System.currentTimeMillis();
        ArrayList<Result> results = sampledPapers
                .stream()
                .parallel()
                .map(p -> testPaper(parser, pgt, p.id))
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

        Assert.assertTrue(results.size() > 5);

        log.info("Authors: {} (Match: {} Invalid: {} Total {})",
                totalHits / (double)totalAuthors, totalHits, totalInvalid, totalAuthors);
        log.info("Titles:  {} (Match: {} Missing: {} Total {})",
                titleMatches / (double)results.size(), titleMatches, titleMissing, results.size());
    }
}
