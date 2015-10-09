package org.allenai.scienceparse;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
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
		
		@Override
		public String toString() {
			String out = "id: " + id + "\r\n";
			out += "title: " + title + "\r\n";
			out += "year: " + year + "\r\n";
			out += Arrays.toString(authors);
			return out;
		}
	}
	
	public ParserGroundTruth(String jsonFile) throws Exception {
		ObjectMapper om = new ObjectMapper();
		ObjectReader r = om.reader(new TypeReference<List<Paper>>() {});
		InputStreamReader isr = new InputStreamReader(new FileInputStream(jsonFile), "UTF-8");
		
		int c = isr.read(); // skip zero-width space if it exists
		if(c != 0xfeff) {
			isr.close();
			isr = new InputStreamReader(new FileInputStream(jsonFile), "UTF-8");
		}
		papers = r.readValue(isr);
		log.info("Read " + papers.size() + " papers.");
		isr.close();
	}
	
	
	
}
