package org.allenai.scienceparse;

import com.gs.collections.api.tuple.Pair;

/**
 * Simple container for extracted metadata.
 * @author dcdowney
 *
 */
public class ExtractedMetadata {
	public String title;
	public Pair<Integer, Integer> titleOffset; //reference to some PDFDoc unknown to this object
	public String authors;
	public Pair<Integer, Integer> authorOffset; //reference to some PDFDoc unknown to this object
}
