package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import org.allenai.scienceparse.ParserGroundTruth.Paper;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;


/**
 * Simple container for extracted metadata.
 * @author dcdowney
 *
 */
@Data
@Slf4j
public class ExtractedMetadata {

	
	public static final String titleTag = "T"; //label used in labeled data
	public static final String authorTag = "A"; //label used in labeled data
	
	transient private static Pattern emailDelimitersRegex = Pattern.compile(",|\\||;");
	
	public String source;
	public String title;
	public Pair<Integer, Integer> titleOffset; //these reference a PDFDoc unknown to this object
	public List<String> authors;
	public List<Pair<Integer, Integer>> authorOffset; //these reference a PDFDoc unknown to this object
	public List<String> emails; //extracted by special (non-CRF) heuristic process
	int year;
	public List<String> raw; //the full paper text
	public List<String> rawReferences; //approximately the references text
	public Pair<List<BibRecord>, List<CitationRecord>> references; //references, with char offsets relative to raw
	
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
		emails = getEmails(toks);
	}
	
	//assumes token contains @
	 public static List<String> tokToMail(String tok) {
		 ArrayList<String> out = new ArrayList<>();
            if (!tok.contains("@")) {
                return null;
            }
            tok = tok.replaceAll("\\P{Print}", "");
            if (tok.contains(":")) {
            	if(tok.split(":").length > 1)
            		tok = tok.split(":")[1];
            }

            String[] parts = tok.split("@");

            if (parts.length == 2) {
                String domain = parts[1];
                String emailStrings = parts[0];
                String[] emails = new String[1];
                if ((emailStrings.startsWith("{") && emailStrings.endsWith("}"))
                        || (emailStrings.startsWith("[") && emailStrings.endsWith
                        ("]")) || emailStrings.contains(",") || emailStrings.contains("|")) {
                    emailStrings = emailStrings.replaceAll("\\{|\\}|\\[|\\]", "");
                    emails = emailStrings.split(emailDelimitersRegex.pattern());
                } else {
                    emails[0] = emailStrings;
                }
//	                System.out.println(line + "\t" + domain);
                for (String email : emails) {
                    out.add(email.trim() + "@" + domain);
                }
            }
            else {
            	log.debug("e-mail parts not 2");
            }
            //log.info("outputting " + out.size() + " addresses");
        return out;
    }

	
	public static List<String> getEmails(List<PaperToken> toks) {
		ArrayList<String> out = new ArrayList<>();
		for(PaperToken t : toks) {
			if(t.getPdfToken() != null) {
				String stT = t.getPdfToken().token;
				if(stT != null && stT.contains("@"))
					out.addAll(tokToMail(stT));
			}
		}
		return out;
	}
	
	public ExtractedMetadata(String sTitle, List<String> sAuthors, Date cDate) {
		title = sTitle;
		authors = sAuthors;
		if(cDate != null) {
			Calendar cal = Calendar.getInstance();
			cal.setTime(cDate);
			year = cal.get(Calendar.YEAR);
		}
		emails = new ArrayList<String>();
	}
	
	public ExtractedMetadata(Paper p) {
		title = p.title;
		authors = Arrays.asList(p.authors);
		year = p.year;
		emails = new ArrayList<String>();
	}
	
	public void setYearFromDate(Date cDate) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(cDate);
		year = cal.get(Calendar.YEAR);		
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
		authors.forEach((String a) -> out.append("A: " + a + "\r\n"));
		emails.forEach((String a) -> out.append("E: " + a + "\r\n"));
		return out.toString();
	}
	
}
