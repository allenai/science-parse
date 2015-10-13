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
import org.allenai.scienceparse.pdfapi.PDFExtractor;
import org.allenai.scienceparse.pdfapi.PDFLine;
import org.allenai.scienceparse.pdfapi.PDFPage;
import org.allenai.scienceparse.pdfapi.PDFToken;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.tuple.Tuples;
import com.sun.media.jfxmedia.logging.Logger;

@Slf4j
public class PDFToCRFInput {

	/**
	 * Returns the index of start (inclusive) and end (exclusive)
	 * of first occurrence of string in seq, or null if not found
	 * @param seq	String to find, assumes tokens are space-delimited 
	 * @return
	 */
	public static Pair<Integer, Integer> findString(List<PaperToken> seq, String toFind) {
		if(seq.size()==0 || toFind.length()==0)
			return null;
		String [] toks = toFind.split(" ");
		if(toks.length==0) { //can happen if toFind is just spaces
			return null;
		}
		int nextToMatch = 0;
		int idx = 0;
		for(PaperToken pt : seq) {
			if(toks[nextToMatch].equalsIgnoreCase(pt.getPdfToken().token)) {
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
	
	private static void addLineTokens(List<PaperToken> accumulator, List<PDFLine> lines, final int pg) {
		int ln =0;
		for(PDFLine l : lines) {
			final int lnF = ln++; //ugh (to get around compile error)
			l.tokens.forEach((PDFToken t) -> accumulator.add(
					new PaperToken(t, lnF, pg))
					);
		}
	}
		
	/**
	 * Returns the PaperToken sequence form of a given PDF document<br>
	 * @param pdd	The PDF Document to convert into instances  
	 * @param heuristicHeader	If true, tries to use heuristic header if found
	 * @return	The data sequence
	 * @throws IOException 
	 */
	public static List<PaperToken> getSequence(PDFDoc pdf, boolean heuristicHeader) throws IOException {
		
		ArrayList<PaperToken> out = new ArrayList<>();
		if(heuristicHeader && pdf.heuristicHeader() != null) {
			List<PDFLine> header = pdf.heuristicHeader();
//			log.info("header lines " + header.size());
//			if(header.size() > 0) {
//				PDFLine last = header.get(header.size()-1);
//				log.info("header last " + last.tokens.get(last.tokens.size()-1).token);
//			}
//			
			addLineTokens(out, header, 0);
		}
		else {
			int pg = 0;
			for(PDFPage p : pdf.getPages()) {
				addLineTokens(out, p.getLines(), pg);
			}
		}
		return out;
	}
	
	public static List<PaperToken> padSequence(List<PaperToken> seq) {
		ArrayList<PaperToken> out = new ArrayList<>();
		out.add(PaperToken.generateStartStopToken());
		out.addAll(seq);
		out.add(PaperToken.generateStartStopToken());
		return out;
	}
	
	public static List<String> padTagSequence(List<String> seq) {
		ArrayList<String> out = new ArrayList<>();
		out.add("<S>");
		out.addAll(seq);
		out.add("</S>");
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
		if(!findAndLabelWith(toks, outTmp, truth.title, ExtractedMetadata.titleTag)) //must have title to be valid
			return null;
		val out = new ArrayList<Pair<PaperToken, String>>();
		out.add(Tuples.pair(PaperToken.generateStartStopToken(),  "<S>"));
		out.addAll(outTmp);
		out.add(Tuples.pair(PaperToken.generateStartStopToken(), "</S>"));
		return out;
	}
	
	public static String stringAt(List<PaperToken> toks, Pair<Integer, Integer> span) {
		List<PaperToken> pts = toks.subList(span.getOne(), span.getTwo());
		List<String> words = pts.stream().map(pt -> (pt.getLine()==-1)?"<S>":pt.getPdfToken().token).collect(Collectors.toList());
		StringBuffer sb = new StringBuffer();
		for(String s : words) {
			sb.append(s);
			sb.append(" ");
		}
		return sb.toString().trim();
	}
	
	public static String getLabelString(List<Pair<PaperToken, String>> seq) {
		return seq.stream().map((Pair<PaperToken, String> a) -> a.getTwo()).collect(Collectors.toList()).toString(); 
	}
	
}
