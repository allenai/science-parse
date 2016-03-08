package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.List;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;
import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;

import lombok.val;

public class ReferencesPredicateExtractor implements CRFPredicateExtractor<String, String> {

  private ParserLMFeatures lmFeats;
  
  public ReferencesPredicateExtractor() {
    this(null);
  }
  
  public ReferencesPredicateExtractor(ParserLMFeatures lmf) {
    lmFeats = lmf;
  }
  
  @Override
  public List<ObjectDoubleMap<String>> nodePredicates(List<String> elems) {
    List<ObjectDoubleMap<String>> out = new ArrayList<>();
    for (int i = 0; i < elems.size(); i++) {
      ObjectDoubleHashMap<String> m = new ObjectDoubleHashMap<>();
      //word features:
      String tok = elems.get(i);
  
      PDFPredicateExtractor.getCaseMasks(tok).forEach(
        (String s) -> m.put(s, 1.0)); //case masks
      if (PDFPredicateExtractor.isStopWord(tok)) {
        m.put("%stop", 1.0); //stop word
        if (m.containsKey("%XXX") || m.containsKey("%Xxx"))
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

      if (lmFeats != null) { //how well does token match title/author gazeetters
        m.put("%tfreq", PDFPredicateExtractor.smoothFreq(tok, this.lmFeats.titleBow));
        m.put("%tffreq", PDFPredicateExtractor.smoothFreq(tok, this.lmFeats.titleFirstBow));
        m.put("%tlfreq", PDFPredicateExtractor.smoothFreq(tok, this.lmFeats.titleLastBow));
        m.put("%afreq", PDFPredicateExtractor.smoothFreq(Parser.trimAuthor(tok), this.lmFeats.authorBow));
        m.put("%affreq", PDFPredicateExtractor.smoothFreq(Parser.trimAuthor(tok), this.lmFeats.authorFirstBow));
        m.put("%alfreq", PDFPredicateExtractor.smoothFreq(Parser.trimAuthor(tok), this.lmFeats.authorLastBow));
        m.put("%bfreq", PDFPredicateExtractor.smoothFreq(tok, this.lmFeats.backgroundBow));
        m.put("%bafreq", PDFPredicateExtractor.smoothFreq(Parser.trimAuthor(tok), this.lmFeats.backgroundBow));
      }
    }
    return out;
  }

  @Override
  public List<ObjectDoubleMap<String>> edgePredicates(List<String> elems) {
    val out = new ArrayList<ObjectDoubleMap<String>>();
    for (int i = 0; i < elems.size() - 1; i++) {
      val odhm = new ObjectDoubleHashMap<String>();
      odhm.put("B", 1.0);
      out.add(odhm);
    }
    return out; //I don't really understand these things.
  }
  
}
