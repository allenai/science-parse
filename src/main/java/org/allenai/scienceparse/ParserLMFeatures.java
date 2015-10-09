package org.allenai.scienceparse;

import java.io.*;
import java.util.List;

import org.allenai.scienceparse.ParserGroundTruth.Paper;

import com.gs.collections.api.map.primitive.ObjectDoubleMap;
import com.gs.collections.impl.map.mutable.primitive.ObjectDoubleHashMap;

public class ParserLMFeatures {
	
	ObjectDoubleHashMap<String> titleBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> authorBow = new ObjectDoubleHashMap<String>();
	ObjectDoubleHashMap<String> backgroundBow = new ObjectDoubleHashMap<String>();
	
	public void fillBow(ObjectDoubleHashMap<String> hm, String s) {
		String [] toks = s.split(" |(?=,)");  //not great
		for(String t : toks) {
			hm.addToValue(t, 1.0);
		}
	}
	
	//paperDirectory must contain pdf docs to use as background language model
	public ParserLMFeatures(List<Paper> ps, int stIdx, int endIdx, File paperDirectory, int approxNumBackgroundDocs) throws IOException {
		for(int i=stIdx; i<endIdx; i++) {
			Paper p = ps.get(i); 
			fillBow(titleBow, p.title);
			for(String a : p.authors)
				fillBow(authorBow, a);
		}
		File [] pdfs = paperDirectory.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File f, String s) {return s.endsWith(".pdf");};
		});
		double samp = ((double)approxNumBackgroundDocs)/((double)pdfs.length);
		for(int i=0; i<pdfs.length;i++) {
			if(Math.random() < samp) {
				fillBow(backgroundBow, Parser.paperToString(pdfs[i]));
			}
		}
	}
}
