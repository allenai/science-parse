package org.allenai.scienceparse;

import java.util.ArrayList;
import java.util.List;

public class ExtractReferences {
	
	private interface BibStractor {
		BibRecord parse(String line);
	}
	
	private static BibStractor [] extractors = new BibStractor [] {
		(String a) -> new BibRecord(a, new ArrayList<String>(), a)
	};
	
	private static int refStart(List<String> paper) {
		for(int i=0; i<paper.size(); i++) {
			String s = paper.get(i);
			if(s.endsWith("References")||s.endsWith("Citations")||s.endsWith("Bibliography"))
				return i;
		}
		return -1;
	}
	
	public static List<BibRecord> findReferences(List<String> paper) {
		ArrayList<BibRecord> out = new ArrayList<>();
		int start = refStart(paper) + 1;
		List<BibRecord> [] results = new List[extractors.length];
		for(int i=start; i<paper.size(); i++) {
			String s = paper.get(i);
			for(int j=0; j<extractors.length;j++) {
				results[j].add(extractors[j].parse(s));
			}
		}
		return out;
	}
	
	public static List<CitationRecord> findCitations(List<String> paper, List<BibRecord> bib) {
		ArrayList<CitationRecord> out = new ArrayList<>();
		return out;
	}
}
