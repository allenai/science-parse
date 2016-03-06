package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.List;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;

import lombok.val;

public class ReferencesPredicateExtractor implements CRFPredicateExtractor<String, String> {

  private ParserLMFeatures lmfeats;
  
  public ReferencesPredicateExtractor(ParserLMFeatures lmf) {
    lmfeats = lmf;
  }
  
  @Override
  public List<ObjectDoubleMap<String>> edgePredicates(List<String> elems) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public List<ObjectDoubleMap<String>> nodePredicates(List<String> elems) {
    val out = new ArrayList<ObjectDoubleMap<String>>();
    for (int i = 0; i < elems.size() - 1; i++) {
      val odhm = new ObjectDoubleHashMap<String>();
      odhm.put("B", 1.0);
      out.add(odhm);
    }
    return out; //I don't really understand these things.
  }
  
}
