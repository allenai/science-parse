package org.allenai.scienceparse;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.tuple.Tuples;
import com.medallia.word2vec.Searcher;
import com.medallia.word2vec.Word2VecModel;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.allenai.datastore.Datastore;
import org.allenai.ml.sequences.crf.CRFPredicateExtractor;
import org.allenai.scienceparse.pdfapi.PDFToken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class PDFPredicateExtractor implements CRFPredicateExtractor<PaperToken, String> {

  public static final List<String> stopWords = Arrays.asList("a", "an", "the", "in", "of", "for", "from", "and", "as", "but",
    "to");
  public static final HashSet<String> stopHash = new HashSet<String>(stopWords);

  private final ParserLMFeatures lmFeats;
  private final Searcher word2vecSearcher;

  public PDFPredicateExtractor() {
    this(null);
  }

  public PDFPredicateExtractor(ParserLMFeatures plf) {
    try {
      final Path word2VecModelPath =
              Datastore.apply().filePath("org.allenai.scienceparse", "Word2VecModel.bin", 1);
      final Word2VecModel word2VecModel = Word2VecModel.fromBinFile(word2VecModelPath.toFile());
      word2vecSearcher = word2VecModel.forSearch();
    } catch(final IOException e) {
      throw new RuntimeException(e);
    }

    lmFeats = plf;
  }

  public static List<String> getCaseMasks(String tok) {
    Pattern Xxx = Pattern.compile("[A-Z][a-z]*");
    Pattern xxx = Pattern.compile("[a-z]+");
    Pattern letters = Pattern.compile("[a-zA-Z]+");
    Pattern dig = Pattern.compile("[0-9]+");
    Pattern hasNum = Pattern.compile(".*[0-9]+.*");
    Pattern letterDot = Pattern.compile("[A-Z]\\.");
    Pattern hasNonAscii = Pattern.compile(".*[^\\p{ASCII}]+.*");
    Pattern wordColon = Pattern.compile("[a-zA-Z]+:");
    Pattern hasAt = Pattern.compile(".*@.*");

    List<Pattern> pats = Arrays.asList(Xxx, xxx, letters, dig, hasNum, letterDot, hasNonAscii, wordColon, hasAt);
    List<String> feats = Arrays.asList("%Xxx", "%xxx", "%letters", "%dig", "%hasNum", "%letDot", "%hasNonAscii", "%capWordColon", "%hasAt");
    ArrayList<String> out = new ArrayList<String>();
    for (int i = 0; i < pats.size(); i++) {
      Pattern p = pats.get(i);
      if (p.matcher(tok).matches()) {
        out.add(feats.get(i));
      }
    }
    return out;
  }

  public static boolean isStopWord(String tok) {
    return stopHash.contains(tok);
  }

  public static float getY(PaperToken t, boolean upper) {
    if (upper)
      return t.getPdfToken().bounds.get(1);
    else
      return t.getPdfToken().bounds.get(3);
  }

  public static double smoothFreq(String tok, ObjectDoubleHashMap<String> hm) {
    double freq = hm.get(tok);
    if (freq > 0.0)
      freq -= 0.6;
    return Math.log10(freq + 0.1);
  }

  private static float height(PDFToken t) {
    return t.bounds.get(3) - t.bounds.get(1);
  }

  private static float width(PDFToken t) {
    return t.bounds.get(0) - t.bounds.get(2);
  }

  public static float getExtreme(List<PaperToken> toks, TokenPropertySelector s, boolean max) {
    float adj = -1.0f;
    float extremeSoFar = Float.NEGATIVE_INFINITY;
    if (max) {
      adj = 1.0f;
    }
    for (PaperToken pt : toks) {
      float propAdj = s.getProp(pt) * adj;
      if (propAdj > extremeSoFar) {
        extremeSoFar = propAdj;
      }
    }
    return extremeSoFar * adj;
  }

  public static float linearNormalize(float f, Pair<Float, Float> rng) {
    if (Math.abs(rng.getTwo() - rng.getOne()) < 0.00000001)
      return 0.5f;
    else
      return (f - rng.getOne()) / (rng.getTwo() - rng.getOne());
  }

  public static Pair<Float, Float> getExtrema(List<PaperToken> toks, TokenPropertySelector s) {
    Pair<Float, Float> out = Tuples.pair(getExtreme(toks, s, false), getExtreme(toks, s, true));
    return out;
  }

  public static float getFixedFont(PaperToken t) {
    float s = t.getPdfToken().fontMetrics.ptSize;
    if (s > 30.0f) //assume it's an error
      return 11.0f;
    else
      return s;
  }

  public double logYDelt(float y1, float y2) {
    return Math.log(Math.max(y1 - y2, 0.00001f));
  }

  //assumes start/stop padded
  @Override
  public synchronized List<ObjectDoubleMap<String>> nodePredicates(List<PaperToken> elems) {
    List<ObjectDoubleMap<String>> out = new ArrayList<>();
    Pair<Float, Float> hBounds = getExtrema(elems.subList(1, elems.size() - 1), (PaperToken t) -> {
      return height(t.getPdfToken());
    });
    Pair<Float, Float> fBounds = getExtrema(elems.subList(1, elems.size() - 1), (PaperToken t) -> {
      return getFixedFont(t);
    });

    for (int i = 0; i < elems.size(); i++) {
      ObjectDoubleHashMap<String> m = new ObjectDoubleHashMap<String>();
      float prevFont = -10.0f;
      float nextFont = -10.0f;
      float prevHeight = -10.0f;
      float nextHeight = -10.0f;
      float prevY = 0.0f;
      float nextY = -1000000.0f;

      int prevLine = -1;
      int nextLine = -1;
      if (i == 0)
        m.put("<S>", 1.0);
      else if (i == elems.size() - 1)
        m.put("</S>", 1.0);
      else {
        if (i != 1) {
          prevLine = elems.get(i - 1).getLine();
          prevFont = getFixedFont(elems.get(i - 1));
          prevHeight = height(elems.get(i - 1).getPdfToken());
          prevY = getY(elems.get(i - 1), false);
        }
        if (i != elems.size() - 2) {
          nextLine = elems.get(i + 1).getLine();
          nextFont = getFixedFont(elems.get(i + 1));
          nextHeight = height(elems.get(i + 1).getPdfToken());
          nextY = getY(elems.get(i + 1), true);
        } else {
          nextY = getY(elems.get(i), false) + height(elems.get(i).getPdfToken()); //guess that next line is height units below
        }
        float font = getFixedFont(elems.get(i));
        float h = height(elems.get(i).getPdfToken());
        int line = elems.get(i).getLine();
        //font-change forward (fcf) or backward (fcb):
        if (font != prevFont)
          m.put("%fcb", 1.0); //binary, 1.0 if there is a font change forward, 0.0 otherwise
        if (font != nextFont)
          m.put("%fcf", 1.0); //font change backward
        if (line != prevLine) {
          m.put("%lcb", 1.0); //line change backward
          m.put("%hGapB", logYDelt(getY(elems.get(i), true), prevY)); //height gap backward
        }
        if (line != nextLine) {
          m.put("%lcf", 1.0); //line change forward
          m.put("%hGapF", logYDelt(nextY, getY(elems.get(i), false))); //height gap forward
        }
        if (Math.abs(Math.abs(nextHeight - h) / Math.abs(nextHeight + h)) > 0.1) { //larger than ~20% height change forward
          m.put("%hcf", 1.0);
        }
        if (Math.abs(Math.abs(prevHeight - h) / Math.abs(prevHeight + h)) > 0.1) { //larger than ~20% height change backward
          m.put("%hcb", 1.0);
        }

        //font value:
        float relativeF = linearNormalize(font, fBounds);
        m.put("%font", relativeF); //font size normalized relative to doc

        m.put("%line", Math.min(line, 10.0)); //cap to max 10 lines
        float relativeH = linearNormalize(h, hBounds);
        m.put("%h", relativeH); //normalized line height

        //word features:
        String tok = elems.get(i).getPdfToken().token;

        getCaseMasks(tok).forEach(
          (String s) -> m.put(s, 1.0)); //case masks
        if (isStopWord(tok)) {
          m.put("%stop", 1.0); //stop word
          if (line != prevLine && (m.containsKey("%XXX") || m.containsKey("%Xxx")))
            m.put("%startCapStop", 1.0); //is a stop word that starts with a capital letter
        } else {
          if (m.containsKey("%xxx")) {
            m.put("%uncapns", 1.0); //is an uncapitalized stop word
          }
        }
        double adjLen = Math.min(tok.length(), 10.0) / 10.0;
        double adjLenSq = (adjLen - 0.5) * (adjLen - 0.5);
        m.put("%adjLen", adjLen); //adjusted word length
        m.put("%adjLenSq", adjLenSq); //adjusted word length squared (?)
        if (line <= 2)
          m.put("%first3lines", 1.0); //is it in the first three lines:
        if (lmFeats != null) { //how well does token match title/author gazeetters
          m.put("%tfreq", smoothFreq(tok, this.lmFeats.titleBow));
          m.put("%tffreq", smoothFreq(tok, this.lmFeats.titleFirstBow));
          m.put("%tlfreq", smoothFreq(tok, this.lmFeats.titleLastBow));
          m.put("%afreq", smoothFreq(Parser.fixupAuthors(tok), this.lmFeats.authorBow));
          m.put("%affreq", smoothFreq(Parser.fixupAuthors(tok), this.lmFeats.authorFirstBow));
          m.put("%alfreq", smoothFreq(Parser.fixupAuthors(tok), this.lmFeats.authorLastBow));
          m.put("%bfreq", smoothFreq(tok, this.lmFeats.backgroundBow));
          m.put("%bafreq", smoothFreq(Parser.fixupAuthors(tok), this.lmFeats.backgroundBow));
        }

        // add the token itself as a feature
        final String token = StringUtils.normalize(elems.get(i).getPdfToken().token);
		m.put("%t=" + token, 1.0);

        if(token.equals("and") || token.equals(","))
          m.put("%and", 1.0);

        // add trigram features
        /*
        final String trigramSourceToken = token + "$";
        for(int j = 0; j <= trigramSourceToken.length() - 3; ++j) {
          final String trigram = trigramSourceToken.substring(j, j + 3);
          final String feature = "%tri=" + trigram;
          m.updateValue(feature, 0.0, d -> d + 1);
        }
        */

        // add word embeddings
        /*
        try {
          final Iterator<Double> vector = word2vecSearcher.getRawVector("token").iterator();
          int j = 0;
          while(vector.hasNext()) {
            final double value = vector.next();
            m.put(String.format("%%emb%03d", j), value);
            j += 1;
          }
        } catch (final Searcher.UnknownWordException e) {
          // do nothing
        }
        */
      }
      out.add(m);
    }

    // print extensive debug information
    if(log.isDebugEnabled()) {
      // calculate a hash out of the tokens, so we can match feature values from different runs
      // based on the tokens
      final String tokens =
              out.stream().map(features ->
                      features.
                              keysView().
                              select(key -> key.startsWith("%t=")).
                              collect(featureName -> featureName.substring(3)).
                              makeString("-")
              ).collect(Collectors.joining(" "));
      final String tokensHashPrefix = String.format("%x", tokens.hashCode());

      log.debug("{} CRF Input for {}", tokensHashPrefix, tokens);
      PrintFeaturizedCRFInput.stringsFromFeaturizedSeq(out, tokensHashPrefix).stream().forEach(log::debug);
    }

    return out;
  }

  @Override
  public List<ObjectDoubleMap<String>> edgePredicates(List<PaperToken> elems) {
    val out = new ArrayList<ObjectDoubleMap<String>>();
    for (int i = 0; i < elems.size() - 1; i++) {
      val odhm = new ObjectDoubleHashMap<String>();
      odhm.put("B", 1.0);
      out.add(odhm);
    }
    return out; //I don't really understand these things.
  }

  private interface TokenPropertySelector {
    float getProp(PaperToken t);
  }
}
