package org.allenai.scienceparse;

import java.util.List;

import com.gs.collections.api.tuple.Pair;

/**
 * Simple container for extracted metadata.
 * @author dcdowney
 *
 */
public class ExtractedMetadata {
	public static final String titleTag = "T"; //label used in labeled data
	public static final String authorTag = "A"; //label used in labeled data
	
	public String title;
	public Pair<Integer, Integer> titleOffset; //reference to some PDFDoc unknown to this object
	public List<String> authors;
	public List<Pair<Integer, Integer>> authorOffset; //reference to some PDFDoc unknown to this object
	int year;
	
	
	public String toString() {
		StringBuffer out = new StringBuffer("T: " + title + "\r\n");
		authors.forEach((String a) -> out.append("A: " + a + "r\n"));
		out.append("\r\n");
		return out.toString();
	}
}
