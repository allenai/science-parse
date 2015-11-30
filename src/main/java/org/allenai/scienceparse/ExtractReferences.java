package org.allenai.scienceparse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.gs.collections.api.tuple.Pair;
import com.gs.collections.impl.tuple.Tuples;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExtractReferences {
	
	CheckReferences cr;
	
	public ExtractReferences(String jsonFile) throws IOException {
		cr = new CheckReferences(jsonFile);
	}
	
	public static abstract class BibStractor  {
		public abstract List<BibRecord> parse(String source);
		public abstract String getCiteRegex();
		public abstract String getCiteDelimiter();
		final BibRecordParser recParser;
		BibStractor(Class c) {
			BibRecordParser b = null;
			try {
				b = (BibRecordParser) c.newInstance();
			}
			catch(Exception e) {
				log.info("Exception " + e.getStackTrace());
			}
			recParser = b;
		}
	}
	
	public interface BibRecordParser {
		public abstract BibRecord parseRecord(String line);
	}
	
	//279
	//378
	//480
	//492
	//606
	
	private static List<BibStractor> extractors = 
			Arrays.asList(new BracketNumber(BracketNumberInitialsQuotedBibRecordParser.class),
			new NamedYear(NamedYearBibRecordParser.class), 
			new NamedYear(NamedYearInParensBibRecordParser.class), 
			new NumberDot(NumberDotYearParensBibRecordParser.class),
			new NumberDot(NumberDotAuthorNoTitleBibRecordParser.class),
			new NumberDot(NumberDotYearNoParensBibRecordParser.class),
			new BracketNumber(BracketNumberInitialsYearParensCOMMAS.class),
			new BracketNumber(BracketNumberBibRecordParser.class));
	
			
	private static class DefaultBibRecordParser implements BibRecordParser{
		public BibRecord parseRecord(String line) {
			return new BibRecord(line, null, null, null, 0);
		}
	}
	
	public static final String authOneName = "\\p{Lu}[\\p{L}'`\\- ]+"; //space for things like De Mori
			
	//pattern for matching single author name, format as in Jones, C. M.
	
	public static final String authLastCommaInitial = authOneName + ", (?:\\p{Lu}\\.-? ?)+";
	
	public static final String authConnect = "(?:(?:, |, and | and )";
	
	public static final String authInitialsLast = "(?:\\p{Lu}\\.?(?:-| )?)+ " + authOneName;
	public static final String authInitialsLastList = authInitialsLast + authConnect + "(?:" + authInitialsLast + "))*";
	
	
	
	public static final String authPlain = authOneName + "(?:\\p{Lu}\\. )?" + authOneName;
	public static final String authPlainList = authPlain + "(?:(?:, and|,) (?:" + authPlain + "))*";
	public static final String authGeneral = "\\p{Lu}[\\p{L}\\.'`\\- ]+";
	public static final String authGeneralList = authGeneral + "(?:(?:; |, |, and |; and | and )" + authGeneral + ")*"; 

	
	private static class BracketNumberInitialsQuotedBibRecordParser implements  BibRecordParser {
		public BracketNumberInitialsQuotedBibRecordParser() {
		}
		//example:
	//	"[1] E. Chang and A. Zakhor, “Scalable video data placement on parallel disk "
//				+ "arrays," in IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, "
//				+ "Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208–221."
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			//String regEx = "\\[([0-9]+)\\] (.*), \\p{Pi}(.*),\\p{Pf} (?:(?:I|i)n )?(.*)\\.?";
			String regEx = "\\[([0-9]+)\\] (.*)(?:,|\\.|:) [\\p{Pi}\"\']+(.*),[\\p{Pf}\"\']+ (?:(?:I|i)n )?(.*)\\.?";
			Matcher m = Pattern.compile(regEx).matcher(line.trim());
			if(m.matches()) {
//				log.info("title: " + m.group(3));
				BibRecord out = new BibRecord(m.group(3), authorStringToList(m.group(2)),
						m.group(4), Pattern.compile(m.group(1)), extractRefYear(m.group(4)));
						return out;
			}
			else
				return null;
		}
	}

	private static class BracketNumberBibRecordParser implements BibRecordParser {
		public BracketNumberBibRecordParser() {
			
		}
		//example:
	//TODO
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			String regEx = "\\[([0-9]+)\\] (" + authInitialsLastList + ")\\. ([^\\.]+)\\. (?:(?:I|i)n )?(.*) ([1-2][0-9]{3})\\.( .*)?";
			Matcher m = Pattern.compile(regEx).matcher(line.trim());
			if(m.matches()) {
//				log.info("year: " + m.group(5));
				BibRecord out = new BibRecord(m.group(3), authorStringToList(m.group(2)),
						m.group(4), Pattern.compile(m.group(1)), extractRefYear(m.group(5)));
						return out;
			}
			else
				return null;
		}
	}
	
	static class NumberDotAuthorNoTitleBibRecordParser implements BibRecordParser {
		public NumberDotAuthorNoTitleBibRecordParser() {}
		//example:
		//1. Jones, C. M.; Henry, E. R.; Hu, Y.; Chan C. K; Luck S. D.; Bhuyan, A.; Roder, H.; Hofrichter, J.; 
		//Eaton, W. A. Proc Natl Acad Sci USA 1993, 90, 11860.
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			String regEx = "([0-9]+)\\. +(" + authLastCommaInitial + 
					"(?:; " + authLastCommaInitial + ")*)" + " ([^0-9]*) ([1-2][0-9]{3})(?:\\.|,[0-9, ]*)(?:.*)";
			
			Matcher m = Pattern.compile(regEx).matcher(line.trim());
			if(m.matches()) {
//				log.info("year string: " + m.group(4));
				BibRecord out = new BibRecord("", authorStringToList(m.group(2)),
						m.group(3), Pattern.compile(m.group(1)), extractRefYear(m.group(4)));
						return out;
			}
			else
				return null;
		}		
	}
	
	private static class NumberDotYearParensBibRecordParser implements  BibRecordParser {
		public NumberDotYearParensBibRecordParser() {
		}
		//example: 
		//TODO
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			String regEx = "([0-9]+)\\. ([^:]+): ([^\\.]+)\\. (?:(?:I|i)n: )?(.*) \\(([0-9]{4})\\)(?: .*)?"; //last part is for header break
			Matcher m = Pattern.compile(regEx).matcher(line.trim());
			if(m.matches()) {
//				log.info("year string: " + m.group(5));
				BibRecord out = new BibRecord(m.group(3), authorStringToList(m.group(2)),
						m.group(4), Pattern.compile(m.group(1)), extractRefYear(m.group(5)));
						return out;
			}
			else
				return null;
		}
	}

	private static class NumberDotYearNoParensBibRecordParser implements  BibRecordParser {
		public NumberDotYearNoParensBibRecordParser() {
		}
		//example: 
		//TODO
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			String regEx = "([0-9]+)\\. (" + authInitialsLastList + "). ([^\\.]+)\\. (?:(?:I|i)n: )?(.*) ([1-2][0-9]{3}).( .*)?"; //last part for header break
			Matcher m = Pattern.compile(regEx).matcher(line.trim());
			if(m.matches()) {
//				log.info("year string: " + m.group(5));
				BibRecord out = new BibRecord(m.group(3), authorStringToList(m.group(2)),
						m.group(4), Pattern.compile(m.group(1)), extractRefYear(m.group(5)));
						return out;
			}
			else
				return null;
		}
	}
	
	private static class NamedYearBibRecordParser implements BibRecordParser {
		//example:
		//STONEBREAKER, M. 1986. A Case for Shared Nothing. Database Engineering 9, 1, 4–9.
		public NamedYearBibRecordParser() {}
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			String regEx1 = "(" + authGeneralList + ") +([1-2][0-9]{3}[a-z]?)\\. ([^\\.]+)\\. (?:(?:I|i)n )?(.*)\\.?";
			Matcher m1 = Pattern.compile(regEx1).matcher(line.trim());
			if(m1.matches()) {
//				log.info("year : " + m1.group(2));
				List<String> authors = authorStringToList(m1.group(1));
				int year = Integer.parseInt(m1.group(2).substring(0, 4));
				String citeStr = NamedYear.getCiteAuthorFromAuthors(authors) + ",? " + m1.group(2);
				BibRecord out = new BibRecord(m1.group(3), authors,
						m1.group(4), Pattern.compile(citeStr, Pattern.CASE_INSENSITIVE), year);
//				BibRecord out = new BibRecord("title", null, null, null, 0);
				
						return out;
			}
			else
				return null;
		}

	}

	private static class NamedYearInParensBibRecordParser implements BibRecordParser {
		//example:
		//STONEBREAKER, M. 1986. A Case for Shared Nothing. Database Engineering 9, 1, 4–9.
		public NamedYearInParensBibRecordParser() {}
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			String regEx2 = "(" + authGeneralList + ") +\\(([1-2][0-9]{3}[a-z]?)\\)\\. ([^\\.]+)\\. (?:(?:I|i)n )?(.*)\\.?";
			Matcher m2 = Pattern.compile(regEx2).matcher(line.trim());
			if(m2.matches()) {
				List<String> authors = authorStringToList(m2.group(1));
				int year = Integer.parseInt(m2.group(2).substring(0, 4));
//				log.info("year: " + year);
				String citeStr = NamedYear.getCiteAuthorFromAuthors(authors) + ",? " + m2.group(2);
				BibRecord out = new BibRecord(m2.group(3), authors,
						m2.group(4), Pattern.compile(citeStr, Pattern.CASE_INSENSITIVE), year);
						return out;				
			}
			else
				return null;
		}
	}
	
	private static class BracketNumberInitialsYearParensCOMMAS implements BibRecordParser {
		public BracketNumberInitialsYearParensCOMMAS() {
		}
		//example:
//		[1] S. Abiteboul, H. Kaplan, and T. Milo, Compact labeling schemes for ancestor queries. Proc. 12th Ann. ACM-SIAM Symp.
//		on Discrete Algorithms (SODA 2001), 547-556.
		public BibRecord parseRecord(String line) {
//			log.info("trying " + line);
			String regEx1 = "\\[([0-9]+)\\] (" + authInitialsLastList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*) +\\(.*([1-2][0-9]{3}).*\\).*";
			String regEx2 = "\\[([0-9]+)\\] (" + authInitialsLastList + ")(?:,|\\.) ([^\\.,]+)(?:,|\\.) (?:(?:I|i)n )?(.*), ([1-2][0-9]{3})\\.";
			Matcher m = Pattern.compile(regEx1).matcher(line.trim());
			Matcher m2 = Pattern.compile(regEx2).matcher(line.trim());
			if(m.matches()) {
	//			log.info("matched with ex1 " + m.group(3));
				BibRecord out = new BibRecord(m.group(3), authorStringToList(m.group(2)),
						m.group(4), Pattern.compile(m.group(1)), extractRefYear(m.group(5)));
						return out;
			}
			else if(m2.matches()) {
//				log.info("matched with ex2 " + m2.group(3));
				BibRecord out = new BibRecord(m2.group(3), authorStringToList(m2.group(2)),
						m2.group(4), Pattern.compile(m2.group(1)), extractRefYear(m2.group(5)));
						return out;
			}
			else
				return null;
		}
	}
	
	
	private static class NamedYear extends BibStractor {
		private final String citeRegex = "(?:\\[|\\()([^\\[\\(\\]\\)]+ [1-2][0-9]{3}[a-z]?)+(?:\\]|\\))";
		private final String citeDelimiter = ";";
		
		NamedYear(Class c) {
			super(c);
		}
		
		public String getCiteRegex() {
			return citeRegex;
		}
		
		public String getCiteDelimiter() {
			return citeDelimiter;
		}
	
		//in regex form
		public static String getCiteAuthorFromAuthors(List<String> authors) {
			if(authors.size() > 2) {
				return getAuthorLastName(authors.get(0)) + " et al\\.";
			}
			else if(authors.size() == 1) {
				return getAuthorLastName(authors.get(0));
			}
			else if(authors.size() == 2) {
				return getAuthorLastName(authors.get(0)) + " and " + getAuthorLastName(authors.get(1));
			}
			return null;
		}
		
		public List<BibRecord> parse(String line) {
			if(line.startsWith("<bb>"))
				line = line.substring(4);
			String [] citesa = line.split("<bb>");
			List<String> cites = Arrays.asList(citesa);
//			log.info(cites.get(0));
			List<BibRecord> out = new ArrayList<BibRecord>();
			for(String s : cites) {
				s = s.replaceAll("<lb>", " ");
				out.add(this.recParser.parseRecord(s));
			}
			out = removeNulls(out);
			return out;
		}
		
	}

	private static class NumberDot extends BracketNumber {
		NumberDot(Class c) {
			super(c);
		}
		public List<BibRecord> parse(String line) {
			line = line.replaceAll("<bb>", "<lb>");
//			log.info("trying with " + line);
			int i=0;
			String tag = "<lb>" + (++i) + ". ";
			List<String> cites = new ArrayList<String>();
			int st = line.indexOf(tag);
			while(line.contains(tag) && st >= 0) {
				tag = "<lb>" + (++i) + ". ";
				int end = line.indexOf(tag, st);
				if(end > 0) {
					cites.add(line.substring(st, end));
				}
				else {
					cites.add(line.substring(st));
				}
				st = end;
			}
			List<BibRecord> out = new ArrayList<BibRecord>();
			for(String s : cites) {
				s = s.replaceAll("<lb>", " ");
				out.add(this.recParser.parseRecord(s));
			}
			out = removeNulls(out);
			return out;
		}
	}
	
	private static class BracketNumber extends BibStractor {
		protected final String citeRegex = "\\[([0-9,]+)\\]";
		protected final String citeDelimiter = ",";
		
		BracketNumber(Class c) {
			super(c);
		}
		
		public String getCiteRegex() {
			return citeRegex;
		}
		
		public String getCiteDelimiter() {
			return citeDelimiter;
		}
		
		public List<BibRecord> parse(String line) {
			line = line.replaceAll("<bb>", "<lb>");
//			log.info("Trying " + line);
			int i=0;
			String tag = "[" + (++i) + "]";
			List<String> cites = new ArrayList<String>();
			int st = line.indexOf(tag);
			while(line.contains(tag)) {
				tag = "<lb>[" + (++i) + "]";
				int end = line.indexOf(tag, st);
				if(end > 0) {
					cites.add(line.substring(st, end));
				}
				else {
					cites.add(line.substring(st));
				}
				st = end;
			}
			List<BibRecord> out = new ArrayList<BibRecord>();
			for(String s : cites) {
				s = s.replaceAll("<lb>", " ");
				out.add(this.recParser.parseRecord(s));
			}
			out = removeNulls(out);
			return out;
		}
	}
	
	
	
	
	
	
	private static int extractRefYear(String sYear) {
		String yearPattern = "[1-2][0-9][0-9][0-9]";
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
	
	
	/**
	 * Takes in a string mentioning several authors, returns normalized list of authors
	 * @param authString
	 * @return
	 */
	public static List<String> authorStringToList(String authString) {
		boolean semiDelim = false;
		if(authString.contains(";")) { //assume semi-colon delimiter
			semiDelim = true;
		}
		
		//figure out whether M. Johnson or Johnson, M.:
		boolean firstLast = false;
		List<String> out = new ArrayList<>();
		if(Pattern.compile("\\p{Lu}\\..*").matcher(authString).matches()) {
			firstLast = true;
		}
//		log.info("auth string: " + authString);
		String [] names;
		if(semiDelim)
			names = authString.split("(;|; and| and | AND | And )+");
		else
			names = authString.split("(,| and | AND | And )+");
//		log.info("names: " + Arrays.toString(names));
		if(firstLast) {
			out = Arrays.asList(names);
		}
		else {
			if(semiDelim) {
				for(int i=0; i<names.length; i++) {
					out.add(ParserGroundTruth.invertAroundComma(names[i]));
				}
			}
			else {
				for(int i=0; i<names.length; i+=2) {
					if(names.length > i+1)
						out.add(names[i+1].trim() + " " + names[i].trim());
					else
						out.add(names[i].trim()); //hope for the best
				}
			}
		}
//		log.info("out: " + out.toString());
		return out;
	}
	
	private static <T> List<T> removeNulls(List<T> in) {
		List<T> out = new ArrayList<T>();
		for(T a : in) {
			if(a != null)
				out.add(a);
		}
		return out;
	}
	
	private static String getAuthorLastName(String authName) {
		int idx = authName.lastIndexOf(" ");
		return authName.substring(idx+1);
	}

	
	
	
	private static int refStart(List<String> paper) {
		for(int i=0; i<paper.size(); i++) {
			String s = paper.get(i);
			if(s.endsWith("References")||s.endsWith("Citations")||s.endsWith("Bibliography")||
					s.endsWith("REFERENCES")||s.endsWith("CITATIONS")||s.endsWith("BIBLIOGRAPHY"))
				return i;
			else if(s.contains("References<lb>")||s.contains("Citations<lb>")||s.contains("Bibliography<lb>")||
					s.contains("REFERENCES<lb>")||s.contains("CITATIONS<lb>")||s.contains("BIBLIOGRAPHY<lb>")) {
				return i-1;
			}
		}
		return -1;
	}
	
	public int numFound(List<BibRecord> brs) {
		int i=0;
		for(BibRecord br : brs) {
			if(cr.hasPaper(br.title, br.author, br.year, br.venue))
				i++;
		}
		return i;
	}
	
	public int longestIdx(List<BibRecord> [] results) {
		int maxLen = -1;
		int idx = -1;
		for(int i=0; i<results.length; i++) {
			int f = 10000*numFound(results[i]) + results[i].size(); //order by num found, then by size
			if(f > maxLen) {
				idx = i;
				maxLen = f;
				log.info(f + "\t" + idx);
			}
		}
		return idx;
	}
	
	/**
	 * Returns the list of BibRecords, plus the extractor that produced them (in order to enable citation parsing)
	 * @param paper
	 * @return
	 */
	public Pair<List<BibRecord>, BibStractor> findReferences(List<String> paper) {
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
		int idx = longestIdx(results);
		return Tuples.pair(results[idx], extractors.get(idx));
	}
	
	
	public static int getIdxOf(List<BibRecord> bib, String citeStr) {
		//note: slow
		for(int i=0; i<bib.size(); i++) {
			
			if(bib.get(i).citeRegEx.matcher(citeStr).matches())
				return i;
		}
		return -1;
	}
	
	//note, also replaces <lb> with spaces in lines with found references
	public static List<CitationRecord> findCitations(List<String> paper, List<BibRecord> bib, BibStractor bs) {
		ArrayList<CitationRecord> out = new ArrayList<>();
		Pattern p = Pattern.compile(bs.getCiteRegex());
//		log.info("looking for pattern " + p.pattern());

		int stop = refStart(paper); //stop at start of refs
		if(stop<0)
			stop = paper.size(); //start of refs not found (should never happen for non-null bibliography...)
		
		for(int i=0; i<stop; i++) {
			String s = paper.get(i).replaceAll("<lb>", " ");
			paper.set(i, s);
			Matcher m = p.matcher(s);
			while(m.find()) {
				String [] citations = m.group(1).split(bs.getCiteDelimiter());
				for(int j=0; j<citations.length; j++) {
					int idx = getIdxOf(bib, citations[j].trim());
					if(idx >=0) {
						out.add(new CitationRecord(i, m.start(), m.end(), idx));
					}
				}
			}
		}
		return out;
	}
	
}
