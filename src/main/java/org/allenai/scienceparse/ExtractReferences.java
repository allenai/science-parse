package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExtractReferences {
	
	public static abstract class BibStractor {
		public abstract List<BibRecord> parse(String source);
		public abstract String getCiteRegex();
		public abstract String getCiteDelimiter();
		final BibRecordParser recParser;
		BibStractor(BibRecordParser rp) {
			recParser = rp;
		}
	}
	
	public static interface BibRecordParser {
		public BibRecord parseRecord(String line);
	}
	
	private static List<BibStractor> extractors = Arrays.asList(new BracketNumber());
	
	private static class DefaultBibRecordParser implements BibRecordParser{
		public BibRecord parseRecord(String line) {
			return new BibRecord(line, null, null, null, 0);
		}
	}

	private static int extractRefYear(String sYear) {
		String yearPattern = " [1-2][0-9][0-9][0-9]";
		Matcher mYear = Pattern.compile(yearPattern).matcher(sYear);
		int a = 0;
		while(mYear.find()) {
			try {
				a = Integer.parseInt(mYear.group().trim());
			} catch(Exception e) {};
			if(a > BibRecord.MINYEAR && a < BibRecord.MAXYEAR)
				return a;
		}
		return a;
	}
	
	private static class InitialFirstQuotedBibRecordParser implements BibRecordParser{
		//example:
	//	"[1] E. Chang and A. Zakhor, “Scalable video data placement on parallel disk "
//				+ "arrays,” in IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, "
//				+ "Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208–221."
		public BibRecord parseRecord(String line) {
			String regEx = "\\[([0-9]+)\\] (.*), \\p{Pi}(.*),\\p{Pf} (?:in )?(.*)\\.?";
			Matcher m = Pattern.compile(regEx).matcher(line.trim());
			if(m.matches()) {
				BibRecord out = new BibRecord(m.group(3), Arrays.asList(m.group(2).split("(,|( and ))")),
						m.group(4), m.group(1), extractRefYear(m.group(4)));
						return out;
			}
			else
				return null;
		}
	}

	
	private static class BracketNumber extends BibStractor {
		private final String citeRegex = "\\[([0-9,]+)\\]";
		private final String citeDelimiter = ",";
		
		BracketNumber() {
			super(new InitialFirstQuotedBibRecordParser());
		}
		
		public String getCiteRegex() {
			return citeRegex;
		}
		
		public String getCiteDelimiter() {
			return citeDelimiter;
		}
		
		public List<BibRecord> parse(String line) {
			line = line.replaceAll("<bb>", "");
			int i=0;
			String tag = "[" + (++i) + "]";
			List<String> cites = new ArrayList<String>();
			while(line.contains(tag)) {
				int st = line.indexOf(tag);
				tag = "[" + (++i) + "]";
				int end = line.indexOf(tag);
				if(end > 0) {
					cites.add(line.substring(st, end));
				}
				else {
					cites.add(line.substring(st));
				}
			}
			List<BibRecord> out = new ArrayList<BibRecord>();
			for(String s : cites) {
				out.add(this.recParser.parseRecord(s));
			}
			return out;
		}
	}
	
	private static int refStart(List<String> paper) {
		for(int i=0; i<paper.size(); i++) {
			String s = paper.get(i);
			if(s.endsWith("References")||s.endsWith("Citations")||s.endsWith("Bibliography")||
					s.endsWith("REFERENCES")||s.endsWith("CITATIONS")||s.endsWith("BIBLIOGRAPHY"))
				return i;
		}
		return -1;
	}
	
	public static List<BibRecord> findReferences(List<String> paper) {
		int start = refStart(paper) + 1;
		List<BibRecord> [] results = new ArrayList[extractors.size()];
		for(int i=0; i<results.length; i++)
			results[i] = new ArrayList<BibRecord>();
		StringBuffer sb = new StringBuffer();
		for(int i=start; i<paper.size(); i++) {
			sb.append("<bb>" + paper.get(i));
		}
		String text = sb.toString();
		for(int i=0; i<results.length; i++) {
			results[i] = extractors.get(i).parse(text);
		}
		return results[0];
	}
	
	public static List<CitationRecord> findCitations(List<String> paper, List<BibRecord> bib) {
		ArrayList<CitationRecord> out = new ArrayList<>();
		return out;
	}
}
