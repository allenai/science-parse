package org.allenai.scienceparse;

import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;
import org.allenai.ml.sequences.crf.conll.ConllFormat.FeatureTemplate;
import org.allenai.ml.sequences.crf.conll.ConllFormat.Row;
import org.allenai.scienceparse.PDFToCRFInput.WordFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;
//TODO: upgrade to pdfbox 2.0 when released

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
	
	private List<Pair<WordFont, String>> sequence; //observation = WordFont, label = String (B|I|O, or null if unlabeled)
	boolean labeled = false;  
	
	public PDFToCRFInput() throws IOException {
		super();
	}
	
	
    static class PDFPredicateExtractor implements CRFPredicateExtractor<WordFont, String> {
    	
    	@Override
		public List<ObjectDoubleMap<String>> nodePredicates(List<WordFont> elems) {
    		List<ObjectDoubleMap<String>> out = new ArrayList<>();
    		Pair<WordFont, String> prev = null;
    		for(val e : elems) {
    			val m = new ObjectDoubleHashMap<String>();
    			if(!e.word.equals("<s>")&&e.font==0.0f) {
    				//font change forward (fcf) or backward (fcb):
    				String state = e.getTwo();
    				String prevState = prev.getTwo();
    				float font = e.getOne().font;
    				float prevFont = prev.getOne().font;
    				//word-state:
    				//font-:
    				String fontState = e.getOne().font + "\t" + state;
    				
    			}
    			prev = e;
    		}
			//word features:
    		
    		
    		return out;
		}

		@Override
		public List<ObjectDoubleMap<String>> edgePredicates(List<WordFont> elems) {
			return null; //I don't really understand these things.
		}
		
	}
	
	/**
	 * Returns the data sequence form of a given PDF document
	 * NOT THREAD SAFE -- different threads must use distinct {@link PDFToCRFInput} instances.
	 * @param pdd	The PDF Document to convert into instances
	 * @param t		The first occurrence of t in document is labeled positive.  If null, unlabeled data is returned.  
	 * @return	The data sequence
	 * @throws IOException 
	 */
	public List<Pair<WordFont, String>> getSequence(PDDocument pdd, String t) throws IOException {
		
		sequence = new ArrayList<>();
		labeled = (t !=null);
		sequence.add(Tuples.pair(new WordFont("<s>", 0.0f), labeled?"O":null)); //start pad
		String s = this.getText(pdd); //this results in examples being populated, via writeString
		sequence.add(Tuples.pair(new WordFont("</s>", 0.0f), labeled?"O":null)); //end pad
		if(t != null) { //set labels
			boolean found = false;
			String [] target = t.split(" ");
			int idx = 0;
			for(int i=0; i<sequence.size(); i++) {
				if(target[idx].equals(sequence.get(i).getOne().word)) {
					if(idx == target.length - 1) { //match
						int start = i-idx;
						for(int j=start; j<=start+idx; j++) {
							sequence.set(j, Tuples.pair(sequence.get(j).getOne(), (j==start)?"B":"I"));
						}
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
		log.info(text + "\t" + textPositions.get(0).getFontSizeInPt());
		WordFont wf = new WordFont(text, textPositions.get(0).getFontSizeInPt());
		String label = null;
		if(labeled)
			label = "O"; //B-I labels will be set after completion
		sequence.add(Tuples.pair(wf, label));
		super.writeString(text, textPositions);
	}

}
