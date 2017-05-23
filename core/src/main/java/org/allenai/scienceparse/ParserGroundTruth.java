package org.allenai.scienceparse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
public class ParserGroundTruth {

  public List<Paper> papers;
  public HashMap<String, Integer> lookup = new HashMap<>();

  private void buildLookup() {
    for (int i = 0; i < papers.size(); i++) {
      lookup.put(papers.get(i).id.substring(4), i);
    }
  }

  public ParserGroundTruth(List<Paper> papers) throws IOException {
    this.papers = papers;
    buildLookup();
  }

  public ParserGroundTruth(final InputStream is) throws IOException {
    try(final BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(is, "UTF-8"))) {

      ObjectMapper om = new ObjectMapper();
      ObjectReader r = om.reader().forType(new TypeReference<Paper>() {});

      papers = new ArrayList<Paper>();
      while (true) {
        final String line = reader.readLine();
        if (line == null)
          break;
        papers.add(r.readValue(line));
      }
    }

    log.info("Read " + papers.size() + " papers.");

    buildLookup();
    papers.forEach((Paper p) -> {
      for (int i = 0; i < p.authors.length; i++)
        p.authors[i] = invertAroundComma(p.authors[i]);
    });
  }

  public ParserGroundTruth(String jsonFile) throws IOException {
    this(new FileInputStream(jsonFile));
  }

  public static String invertAroundComma(String in) {
    String[] fields = in.split(",");
    if (fields.length == 2)
      return (fields[1] + " " + fields[0]).trim();
    else
      return in;
  }

  public Paper forKey(String key) {
    if (!lookup.containsKey(key)) {
      log.info("key not found: " + key);
      return null;
    }
    return papers.get(lookup.get(key));
  }

  @Data
  public static class Paper {
    String id;
    String url;
    String title;
    String[] authors;
    int year;
    String venue;
  }
}
