package org.allenai.scienceparse;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ParserGroundTruth {
	
	public List<Paper> papers;
	
	@Data
	public static class Paper {
		String id;
		String url;
		String title;
		String [] authors;
		int year;
		String venue;
		
	}
	
	public ParserGroundTruth(String jsonFile) throws Exception {
		ObjectMapper om = new ObjectMapper();
		ObjectReader r = om.reader(Paper[].class);
		InputStreamReader isr = new InputStreamReader(new FileInputStream(jsonFile), "UTF-8");
		
		int c = isr.read(); // skip zero-width space if it exists
		if(c != 0xfeff) {
			isr.close();
			isr = new InputStreamReader(new FileInputStream(jsonFile), "UTF-8");
		}
		papers = Arrays.asList(r.readValue(isr));
		log.info("Read " + papers.size() + " papers.");
		isr.close();
	}
	
	
	
}
