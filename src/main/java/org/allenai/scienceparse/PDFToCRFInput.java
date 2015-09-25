package org.allenai.scienceparse;

import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.allenai.ml.sequences.crf.CRFPredicateExtractor;
import org.allenai.scienceparse.pdfapi.PDFDoc;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;
import org.allenai.scienceparse.pdfapi.PDFToken;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
//TODO: upgrade to pdfbox 2.0 when released

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.tuple.Tuples;

@Slf4j
public class PDFToCRFInput {

	/**
	 * Returns the index of start (inclusive) and end (exclusive)
	 * of first occurrence of string in seq, or null if not found
	 * @param seq	String to find, assumes tokens are space-delimited 
	 * @return
	 */
	public static Pair<Integer, Integer> findString(List<PaperToken> seq, String toFind) {
		String [] toks = toFind.split(" ");
		int nextToMatch = 0;
		int idx = 0;
		for(PaperToken pt : seq) {
			if(toks[nextToMatch].equals(pt.getPdfToken().token)) {
				nextToMatch++;
			}
			else {
				nextToMatch = 0;
			}
			idx++;
			if(nextToMatch==toks.length)
				return Tuples.pair(idx-toks.length, idx);
		}
		return null;
	}
	
	/**
	 * Returns the PaperToken sequence form of a given PDF document<br>
	 * @param pdd	The PDF Document to convert into instances  
	 * @return	The data sequence
	 * @throws IOException 
	 */
	public static List<PaperToken> getSequence(PDFDoc pdf) throws IOException {
		
		ArrayList<PaperToken> out = new ArrayList<>();
		int pg = 0;
		for(PDFPage p : pdf.getPages()) {
			int ln = 0;
			for(PDFLine l : p.getLines()) {
				l.tokens.forEach((PDFToken t) -> out.add(
						new PaperToken(t, ln, pg))
						);
			}
		}
		return out;
	}
	
	/**
	 * Labels the (first occurrence of) given target in seq with given label
	 * @param seq	The sequence
	 * @param seqWLabel	The same sequence with labels
	 * @param target	
	 * @param labelStem
	 * @return	True if target was found in seq, false otherwise
	 */
	public static boolean findAndLabelWith(List<PaperToken> seq, List<Pair<PaperToken, String>> seqLabeled, String target, String labelStem) {
		Pair<Integer, Integer> loc = findString(seq, target);
		if(loc == null)
			return false;
		else {
			if(loc.getOne() == loc.getTwo() - 1) {
				Pair<PaperToken, String> t = seqLabeled.get(loc.getOne());
				seqLabeled.set(loc.getOne(), Tuples.pair(t.getOne(), "W_" + labelStem));
			}
			else {
				for(int i=loc.getOne(); i<loc.getTwo();i++) {
					Pair<PaperToken, String> t = seqLabeled.get(i);
					seqLabeled.set(i, Tuples.pair(t.getOne(), 
							(i==loc.getOne()?"B_" + labelStem:(i==loc.getTwo()-1?"E_" + labelStem:"I_" + labelStem))));
				}
			}
		return true;
		}
		
	}
	
	/**
	 * Returns the given tokens in a new list with labeled ground truth attached
	 * according to the given reference metadata.
	 * Only labels positive the first occurrence of each ground-truth string.
	 * <br>
	 * Labels defined in ExtractedMetadata
	 * @param toks
	 * @param truth
	 * @return
	 */
	public static List<Pair<PaperToken, String>> labelMetadata(List<PaperToken> toks, ExtractedMetadata truth) {
		val outTmp = new ArrayList<Pair<PaperToken, String>>();
		for(PaperToken t : toks) {
			outTmp.add(Tuples.pair(t, "O"));
		}
		truth.authors.forEach((String s) -> findAndLabelWith(toks, outTmp, s, ExtractedMetadata.authorTag));
		findAndLabelWith(toks, outTmp, truth.title, ExtractedMetadata.titleTag);
		val out = new ArrayList<Pair<PaperToken, String>>();
		out.add(Tuples.pair(null,  "<S>"));
		out.addAll(outTmp); //yuck
		out.add(Tuples.pair(null, "</S>"));
		return out;
	}
	
	public static String stringAt(List<PaperToken> toks, Pair<Integer, Integer> span) {
		List<PaperToken> pts = toks.subList(span.getOne(), span.getTwo());
		List<String> words = pts.stream().map(pt -> pt.getPdfToken().token).collect(Collectors.toList());
		StringBuffer sb = new StringBuffer();
		for(String s : words) {
			sb.append(s);
			sb.append(" ");
		}
		return sb.toString().trim();
	}
	
	public static String labelString(List<Pair<PaperToken, String>> seq) {
		return seq.stream().map((Pair<PaperToken, String> a) -> a.getTwo()).collect(Collectors.toList()).toString(); 
	}
	
}
