package org.allenai.scienceparse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.allenai.scienceparse.ParserGroundTruth.Paper;

import com.gs.collections.impl.set.mutable.primitive.LongHashSet;

public class CheckReferences {
	private LongHashSet paperHashCodes = new LongHashSet();
	
	public int getHashSize() {
		return paperHashCodes.size();
	}
	
	public CheckReferences(String jsonFile) throws IOException {
		ParserGroundTruth pgt = new ParserGroundTruth(jsonFile);
		addPapers(pgt.papers);
	}
	
	public void addPaper(String title, List<String> authors, int year, String venue) {
		paperHashCodes.add(getHashCode(title, authors, year, venue));
	}
	
	public boolean hasPaper(String title, List<String> authors, int year, String venue) {
		return paperHashCodes.contains(getHashCode(title, authors, year, venue));
	}
	
	public long getHashCode(String title, List<String> authors, int year, String venue) {
		title = Parser.processTitle(title);
		authors = Parser.lastNames(authors);
		
		long hashCode = ((long)authors.hashCode()) * ((long)Integer.MAX_VALUE) + ((long)title.hashCode()) 
				+ ((long)Integer.hashCode(year));
		return hashCode;
	}
	
	public void addPapers(List<Paper> papers) {
		for(Paper p : papers) {
			addPaper(p.title, Arrays.asList(p.authors), p.year, p.venue);
		}
	}
	
}
