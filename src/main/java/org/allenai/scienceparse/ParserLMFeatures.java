package org.allenai.scienceparse;

import java.io.*;
import java.util.List;

import org.allenai.scienceparse.ParserGroundTruth.Paper;

import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.set.mutable.UnifiedSet;
import com.sun.media.jfxmedia.logging.Logger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ParserLMFeatures implements Serializable {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	ObjectDoubleHashMap<String> titleBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> titleFirstBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> titleLastBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> authorBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> authorFirstBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> authorLastBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> backgroundBow = new ObjectDoubleHashMap<String>();
	
	
	public int fillBow(ObjectDoubleHashMap<String> hm, String s, ObjectDoubleHashMap<String> firstHM, ObjectDoubleHashMap<String> lastHM) {
		int ct = 0;
		if(s != null) {
			String [] toks = s.split(" |(?=,)");  //not great
			if(toks.length > 0) {
				if(firstHM != null)
					firstHM.addToValue(toks[0], 1.0);
				if(lastHM != null)
					lastHM.addToValue(toks[toks.length-1], 1.0);
			}
			for(String t : toks) {
				hm.addToValue(t, 1.0);
				ct++;
			}
		}
		return ct;
	}
	
	public int fillBow(ObjectDoubleHashMap<String> hm, String s) {
		return fillBow(hm, s, null, null);
	}
	
	public ParserLMFeatures() {
		
	}
	
	//paperDirectory must contain pdf docs to use as background language model
	public ParserLMFeatures(List<Paper> ps, UnifiedSet<String> idsToExclude, int stIdx, int endIdx, File paperDirectory, int approxNumBackgroundDocs) throws IOException {
		log.info("excluding " + idsToExclude.size() + " paper ids from lm features.");
		for(int i=stIdx; i<endIdx; i++) {
			Paper p = ps.get(i); 
			if(!idsToExclude.contains(p.id)) {
				fillBow(titleBow, p.title, titleFirstBow, titleLastBow);
				for(String a : p.authors)
					fillBow(authorBow, a, authorFirstBow, authorLastBow);
			}
		}
		File [] pdfs = paperDirectory.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File f, String s) {return s.endsWith(".pdf");};
		});
		double samp = ((double)approxNumBackgroundDocs)/((double)pdfs.length);
		int ct = 0;
		for(int i=0; i<pdfs.length;i++) {
			if(Math.random() < samp) {
				ct += fillBow(backgroundBow, Parser.paperToString(pdfs[i]));
			}
		}
		log.info("Gazetteer loaded with " + ct + " tokens.");
	}
}
