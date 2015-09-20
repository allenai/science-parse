package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.sun.javafx.runtime.async.AsyncOperationListener;

import lombok.val;

public class PDFPredicateExtractor implements CRFPredicateExtractor<PaperToken, String> {
	
	public static List<String> getCaseMasks(String tok) {
		Pattern Xxx = Pattern.compile("^[A-Z][a-z]*$");
		Pattern xxx = Pattern.compile("^[a-z]+$");
		Pattern dig = Pattern.compile("^[0-9]+$");
		Pattern hasNum = Pattern.compile("[0-9]+");
		
		List<Pattern> pats = Arrays.asList(Xxx, xxx, dig, hasNum);
		List<String> feats = Arrays.asList("%Xxx", "%xxx", "%dig", "%hasNum");
		ArrayList<String> out = new ArrayList<String>();
		for(int i=0; i<pats.size(); i++) {
			Pattern p = pats.get(i);
			if(p.matcher(tok).matches()) {
				out.add(feats.get(i));
			}
		}
		return out;
	}
	
	//assumes start/stop padded
	@Override
	public List<ObjectDoubleMap<String>> nodePredicates(List<PaperToken> elems) {
		List<ObjectDoubleMap<String>> out = new ArrayList<>();
		
		for(int i=0; i<elems.size(); i++) {
			ObjectDoubleHashMap<String> m = new ObjectDoubleHashMap<String>();
			float prevFont = -10.0f;
			float nextFont = -10.0f;
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
				}
				if(i!=elems.size() - 2) {
					nextLine = elems.get(i+1).getLine();
					nextFont = elems.get(i+1).getPdfToken().fontMetrics.ptSize;
				}
				float font = elems.get(i).getPdfToken().fontMetrics.ptSize;
				int line = elems.get(i).getLine();
				//font-change forward (fcf) or backward (fcb):
				if(font!=prevFont)
					m.put("%fcb", 1.0);
				if(font!=nextFont)
					m.put("%fcf", 1.0);
				if(line!=prevLine)
					m.put("%lcb", 1.0);
				if(line!=nextLine)
					m.put("lcf", 1.0);
				//font value:
				m.put("%font", font);
				
				//word features:
				getCaseMasks(elems.get(i).getPdfToken().token).forEach(
						(String s) -> m.put(s, 1.0));
				
				//m.put(elems.get(i).getPdfToken().token, 1.0);
			}
			out.add(m);
		}
		return out;
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

