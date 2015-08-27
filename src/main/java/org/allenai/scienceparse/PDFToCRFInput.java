package org.allenai.scienceparse;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.allenai.scienceparse.PDFToCRFInput.WordFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;
//TODO: upgrade to pdfbox 2.0 when released

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;
import com.gs.collections.impl.tuple.primitive.IntIntPairImpl;

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
	
	//labels = <String> B, I, O
	//observations = WordFont (i.e token and font size)
	//features = <String> lcase token, font size as string, [cross product of transition (nine options) and font size change (<>=), for total of 27 features]  
	
	public PDFToCRFInput() throws IOException {
		super();
	}
	
	/**
	 * NOT THREAD SAFE -- different threads must use distinct {@link PDFToCRFInput} instances.
	 * @param pdd	The PDF Document to convert into instances
	 * @param t		The first occurrence of t in document is labeled positive.  If null, unlabeled data is returned.  
	 * @return
	 * @throws IOException 
	 */
	public List<Pair<WordFont, String>> getSequence(PDDocument pdd, String t) throws IOException {
		
		sequence = new ArrayList<>();
		labeled = (t !=null);
		String s = this.getText(pdd); //this results in examples being populated, via writeString
		
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
