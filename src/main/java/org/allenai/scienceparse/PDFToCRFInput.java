package org.allenai.scienceparse;

import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.tuple.Tuples;

@Slf4j
public class PDFToCRFInput extends PDFTextStripper {

	public static class WordFont {
		public String word;
		public float font;
		
		public WordFont(String w, float f) {
			word = w;
			font = f;
		}
	}
	
	private List<Pair<WordFont, String>> sequence; //observation = WordFont, label = String (B|I|W|E|O, or null if unlabeled)
	boolean labeled = false;  
	
	public PDFToCRFInput() throws IOException {
		super();
	}
	
	
    static class PDFPredicateExtractor implements CRFPredicateExtractor<WordFont, String> {
    	
    	@Override
		public List<ObjectDoubleMap<String>> nodePredicates(List<WordFont> elems) {
    		List<ObjectDoubleMap<String>> out = new ArrayList<>();
    		
    		for(int i=0; i<elems.size(); i++) {
    			ObjectDoubleHashMap<String> m = new ObjectDoubleHashMap<String>();
    			float prevFont = -10.0f;
    			float nextFont = -10.0f;
    			if(i!=0) {
    				prevFont = elems.get(i-1).font;
    			}
    			if(i!=elems.size() - 1) {
    				nextFont = elems.get(i+1).font;
    			}
    			float font = elems.get(i).font;
				//font-change forward (fcf) or backward (fcb):
				if(font!=prevFont)
					m.put("%fcb", 1.0);
				if(font!=nextFont)
					m.put("%fcf", 1.0);
				//font value:
				m.put("%font", font);
				//word features:
				m.put(elems.get(i).word, 1.0);
				out.add(m);
    		}
    		return out;
		}

		@Override
		public List<ObjectDoubleMap<String>> edgePredicates(List<WordFont> elems) {
			val out = new ArrayList<ObjectDoubleMap<String>>();
			for(int i=0; i<elems.size() - 1; i++) {
				val  odhm = new ObjectDoubleHashMap<String>();
				odhm.put("B", 1.0);
				out.add(odhm);				
			}
			return out; //I don't really understand these things.
		}
		
	}
	
	/**
	 * Returns the data sequence form of a given PDF document<br>
	 * NOT THREAD SAFE -- different threads must use distinct {@link PDFToCRFInput} instances.
	 * @param pdd	The PDF Document to convert into instances
	 * @param t		The first occurrence of t in document is labeled positive.  If null, unlabeled data is returned.  
	 * @return	The data sequence
	 * @throws IOException 
	 */
	public List<Pair<WordFont, String>> getSequence(PDDocument pdd, String t) throws IOException {
		
		sequence = new ArrayList<>();
		labeled = (t !=null);
		sequence.add(Tuples.pair(new WordFont("<s>", 0.0f), labeled?"<S>":null)); //start pad
		String s = this.getText(pdd); //this results in examples being populated, via writeString
		sequence = sequence.subList(0, 60);
		sequence.add(Tuples.pair(new WordFont("</s>", 0.0f), labeled?"</S>":null)); //end pad
		if(t != null) { //set labels
			boolean found = false;
			String [] target = t.split(" ");
			int idx = 0;
			for(int i=0; i<sequence.size(); i++) {
				if(target[idx].equals(sequence.get(i).getOne().word)) {
					if(idx == target.length - 1) { //match
						int start = i-idx;
						if(target.length==1) {
							sequence.set(start, Tuples.pair(sequence.get(start).getOne(), "W"));
						}
						for(int j=start; j<=start+idx; j++) {
							sequence.set(j, Tuples.pair(sequence.get(j).getOne(), (j==start)?"B":((j==start+idx)?"E":"I")));
						}
//						if(target.length==1) {
//							sequence.set(start, Tuples.pair(sequence.get(start).getOne(), "I"));
//						}
//						for(int j=start; j<=start+idx; j++) {
//							sequence.set(j, Tuples.pair(sequence.get(j).getOne(), (j==start)?"I":((j==start+idx)?"I":"I")));
//						}
						found = true;
						break;
					}
					else {
						idx++;
					}
				}
			}
			if(!found)
				log.warn("Expected target string " + t + " not found in document.");
		}
		
		return sequence;
	}

	@Override
	protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
		WordFont wf = new WordFont(text, textPositions.get(0).getFontSizeInPt());
		String label = null;
		if(labeled)
			label = "O"; //B-I labels will be set after completion
		sequence.add(Tuples.pair(wf, label));
		super.writeString(text, textPositions);
	}

}
