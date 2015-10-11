package org.allenai.scienceparse;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.util.ObjectIdMap;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ParserGroundTruth {
	
	public List<Paper> papers;
	
	public HashMap<String, Integer> lookup;
	
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
	
	public Paper forKey(String key) {
		if(!lookup.containsKey(key)) {
			log.info("key not found: " + key);
			return null;
		}
		return papers.get(lookup.get(key));
	}
	
	private String invertAroundComma(String in) {
		String [] fields = in.split(",");
		if(fields.length==2)
			return (fields[1] + " " + fields[0]).trim();
		else
			return in;
	}
	
	public ParserGroundTruth(String jsonFile) throws IOException {
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
		lookup = new HashMap<>();
		for(int i=0; i<papers.size(); i++) {
			lookup.put(papers.get(i).id.substring(4), i);
		}
		papers.forEach((Paper p) -> {for(int i=0; i<p.authors.length;i++) p.authors[i] = invertAroundComma(p.authors[i]);});
		
	}
	
	
	
}
