package org.allenai.scienceparse;

import java.io.*;
import java.util.List;

import org.allenai.scienceparse.ParserGroundTruth.Paper;

import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;
import com.gs.collections.impl.set.mutable.UnifiedSet;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ParserLMFeatures implements Serializable {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	ObjectDoubleHashMap<String> titleBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> authorBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> backgroundBow = new ObjectDoubleHashMap<String>();
	
	public int fillBow(ObjectDoubleHashMap<String> hm, String s) {
		int ct = 0;
		if(s != null) {
			String [] toks = s.split(" |(?=,)");  //not great
			for(String t : toks) {
				hm.addToValue(t, 1.0);
				ct++;
			}
		}
		return ct;
	}
	
	//paperDirectory must contain pdf docs to use as background language model
	public ParserLMFeatures(List<Paper> ps, UnifiedSet<String> idsToExclude, int stIdx, int endIdx, File paperDirectory, int approxNumBackgroundDocs) throws IOException {
		for(int i=stIdx; i<endIdx; i++) {
			Paper p = ps.get(i); 
			if(!idsToExclude.contains(p.id)) {
				fillBow(titleBow, p.title);
				for(String a : p.authors)
					fillBow(authorBow, a);
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
