package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.allenai.scienceparse.ParserGroundTruth.Paper;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;

import lombok.RequiredArgsConstructor;
import lombok.Setter;


/**
 * Simple container for extracted metadata.
 * @author dcdowney
 *
 */
public class ExtractedMetadata {
	public static final String titleTag = "T"; //label used in labeled data
	public static final String authorTag = "A"; //label used in labeled data
	
	public String source;
	public String title;
	public Pair<Integer, Integer> titleOffset; //reference to some PDFDoc unknown to this object
	public List<String> authors;
	public List<Pair<Integer, Integer>> authorOffset; //reference to some PDFDoc unknown to this object
	int year;
	
	@RequiredArgsConstructor
	public static class LabelSpan {
		public final String tag;
		public final Pair<Integer, Integer> loc; //(inclusive, exclusive)
	}
	
	/**
	 * Constructs ExtractedMetadata from given text and labels
	 * @param toks
	 * @param labels
	 */
	public ExtractedMetadata(List<PaperToken> toks, List<String> labels) {
		List<LabelSpan> lss = getSpans(labels);
		authors = new ArrayList<String>();
		for(LabelSpan ls : lss) {
			if(title == null && ls.tag.equals(titleTag)) {
				title = PDFToCRFInput.stringAt(toks, ls.loc);
			}
			else if(ls.tag.equals(authorTag)) {
				authors.add(PDFToCRFInput.stringAt(toks, ls.loc));
			}
		}
	}
	
	public ExtractedMetadata(String sTitle, List<String> sAuthors, Date cDate) {
		title = sTitle;
		authors = sAuthors;
		if(cDate != null) {
			Calendar cal = Calendar.getInstance();
			cal.setTime(cDate);
			year = cal.get(Calendar.YEAR);
		}
	}
	
	public ExtractedMetadata(Paper p) {
		title = p.title;
		authors = Arrays.asList(p.authors);
		year = p.year;
	}
	
	public static List<LabelSpan> getSpans(List<String> labels) {
		ArrayList<LabelSpan> out = new ArrayList<LabelSpan>();
		int st = -1;
		String curTag = "";
		for(int i=0; i<labels.size();i++) {
			String lab = labels.get(i);
			if(lab.equals("O")) {
				st = -1;
			}
			else if(lab.startsWith("B_")) {
				st = i;
				curTag = lab.substring(2);
			}
			else if(lab.startsWith("I_")) {
				String t = lab.substring(2);
				if(!curTag.equals(t)) { //mis-matched tags, do not extract
					st = -1;
				}
			}
			else if(lab.startsWith("E_")) {
				String t = lab.substring(2);
				if(curTag.equals(t)&&st >=0) {
					LabelSpan ls = new LabelSpan(curTag, (Pair<Integer, Integer>)Tuples.pair(st, i+1));
					out.add(ls);
					st = -1;
				}
			}
			else if(lab.startsWith("W_")) {
				String t = lab.substring(2);
				LabelSpan ls = new LabelSpan(t, (Pair<Integer, Integer>)Tuples.pair(i, i+1));
				out.add(ls);
				st = -1;
			}
		}
		return out;
	}
	
	public String toString() {
		StringBuffer out = new StringBuffer("T: " + title + "\r\n");
		authors.forEach((String a) -> out.append("A: " + a + "r\n"));
		return out.toString();
	}
	
}
