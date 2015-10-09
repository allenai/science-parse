package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;
import org.allenai.scienceparse.pdfapi.PDFToken;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PDFPredicateExtractor implements CRFPredicateExtractor<PaperToken, String> {
	
	public static final List<String> stopWords = Arrays.asList("a", "an", "the", "in", "of", "for", "from", "and", "as", "but",
			"to");
	public static final HashSet<String> stopHash = new HashSet<String>(stopWords);
	
	private ParserLMFeatures lmFeats;
	
	public PDFPredicateExtractor() {
		
	}
	
	public PDFPredicateExtractor(ParserLMFeatures plf) {
		lmFeats = plf;
	}
	
	public static List<String> getCaseMasks(String tok) {
		Pattern Xxx = Pattern.compile("[A-Z][a-z]*");
		Pattern xxx = Pattern.compile("[a-z]+");
		Pattern dig = Pattern.compile("[0-9]+");
		Pattern hasNum = Pattern.compile(".*[0-9]+.*");
		Pattern letterDot = Pattern.compile("[A-Z]\\.");
		Pattern hasNonAscii = Pattern.compile(".*[^\\p{ASCII}]+.*");
		
		List<Pattern> pats = Arrays.asList(Xxx, xxx, dig, hasNum, letterDot, hasNonAscii);
		List<String> feats = Arrays.asList("%Xxx", "%xxx", "%dig", "%hasNum", "%letDot", "%hasNonAscii");
		ArrayList<String> out = new ArrayList<String>();
		for(int i=0; i<pats.size(); i++) {
			Pattern p = pats.get(i);
			if(p.matcher(tok).matches()) {
				out.add(feats.get(i));
			}
		}
		return out;
	}
	
	public static boolean isStopWord(String tok) {
		return stopHash.contains(tok);
	}
	
	private float height(PDFToken t) {
		return t.bounds.get(1) - t.bounds.get(3);
	}
	
	private float width(PDFToken t) {
		return t.bounds.get(0) - t.bounds.get(2);
	}
	
	//assumes start/stop padded
	@Override
	public List<ObjectDoubleMap<String>> nodePredicates(List<PaperToken> elems) {
		List<ObjectDoubleMap<String>> out = new ArrayList<>();
		//log.info("called with " + elems.size() + " tokens.");
		for(int i=0; i<elems.size(); i++) {
			ObjectDoubleHashMap<String> m = new ObjectDoubleHashMap<String>();
			float prevFont = -10.0f;
			float nextFont = -10.0f;
			float prevHeight = -10.0f;
			float nextHeight = -10.0f;
			
			int prevLine = -1;
			int nextLine = -1;
			if(i==0)
				m.put("<S>", 1.0);
			else if(i==elems.size()-1)
				m.put("</S>", 1.0);
			else {
				if(i!=1) {
					prevLine = elems.get(i-1).getLine();
					prevFont = elems.get(i-1).getPdfToken().fontMetrics.ptSize;
					prevHeight = height(elems.get(i-1).getPdfToken());
				}
				if(i!=elems.size() - 2) {
					nextLine = elems.get(i+1).getLine();
					nextFont = elems.get(i+1).getPdfToken().fontMetrics.ptSize;
					nextHeight = height(elems.get(i+1).getPdfToken());
				}
				float font = elems.get(i).getPdfToken().fontMetrics.ptSize;
				float h = height(elems.get(i).getPdfToken());
				int line = elems.get(i).getLine();
				//font-change forward (fcf) or backward (fcb):
				if(font!=prevFont)
					m.put("%fcb", 1.0);
				if(font!=nextFont)
					m.put("%fcf", 1.0);
				if(line!=prevLine)
					m.put("%lcb", 1.0);
				if(line!=nextLine)
					m.put("%lcf", 1.0);
				if(Math.abs(Math.abs(nextHeight - h)/Math.abs(nextHeight + h)) > 0.1) { //larger than ~20% change
					m.put("%hcf", 1.0);
				}
				if(Math.abs(Math.abs(prevHeight - h)/Math.abs(prevHeight + h)) > 0.1) { 
					m.put("%hcb", 1.0);
				}
				
				//font value:
				m.put("%font", font);
				m.put("%line", line);
				m.put("%h", h);
				
				//word features:
				String tok = elems.get(i).getPdfToken().token;
				
				getCaseMasks(tok).forEach(
						(String s) -> m.put(s, 1.0));
				if(isStopWord(tok)) {
					m.put("%stop", 1.0);
					if(line!=prevLine && (m.containsKey("%XXX")||m.containsKey("%Xxx")))
						m.put("%startCapStop", 1.0);
				}
				else {
					if(m.containsKey("%xxx")) {
						m.put("%uncapns", 1.0);
					}
				}
				if(line <= 2)
					m.put("%first3lines", 1.0);
				if(lmFeats != null) {
					m.put("%tfreq", smoothFreq(tok, this.lmFeats.titleBow));
					m.put("%afreq", smoothFreq(tok, this.lmFeats.authorBow));
					m.put("%bfreq", smoothFreq(tok, this.lmFeats.backgroundBow));
//					log.info("features for " + tok);
//					log.info(m.toString());
				}
//				m.put("%t=" + elems.get(i).getPdfToken().token.toLowerCase(), 1.0);
			}
			out.add(m);
		}
		return out;
	}
	
	public static double smoothFreq(String tok, ObjectDoubleHashMap<String> hm) {
		double freq = hm.get(tok);
		if(freq > 0.0)
			freq -= 0.5;
		return freq;
	}

	@Override
	public List<ObjectDoubleMap<String>> edgePredicates(List<PaperToken> elems) {
		val out = new ArrayList<ObjectDoubleMap<String>>();
		for(int i=0; i<elems.size() - 1; i++) {
			val  odhm = new ObjectDoubleHashMap<String>();
			odhm.put("B", 1.0);
			out.add(odhm);				
		}
		return out; //I don't really understand these things.
	}
	
	public static void main(String [] args) throws Exception {
	
	}
	
	
}

