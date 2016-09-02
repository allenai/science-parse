package org.allenai.scienceparse;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.allenai.scienceparse.ExtractedMetadata.LabelSpan;

import com.gs.collections.impl.map.mutable.primitive.ObjectIntHashMap;
import com.gs.collections.impl.set.mutable.primitive.LongHashSet;
import com.gs.collections.impl.tuple.Tuples;
import com.sun.media.jfxmedia.logging.Logger;

import lombok.extern.slf4j.Slf4j;

/**
 * Holds gazetteers of journal names, person names, countries, etc.
 * Note: only retains gazetteer entries with length at most MAXLENGTH.
 *
 */

@Slf4j
public class GazetteerFeatures {
  private static final long serialVersionUID = 1L;

  private LongHashSet [] hashSets; //each element represents a gazetteer, the long hashcodes of contained strings
  
  private String [] hashNames;
  
  private static int MAXLENGTH = 7; //maximum length (in words) of any gazetteer entry
  
  /**
   * Reads in string gazetteers, assumed to be one entry per line, one gazetteer per file in given directory.
   * @param inDir
   * @throws Exception
   */
  public GazetteerFeatures(String inDir) throws IOException {
    File [] files = (new File(inDir)).listFiles();
    hashSets = new LongHashSet[files.length];
    hashNames = new String[files.length];
    for(int i=0; i<files.length; i++) {
      hashSets[i] = readGazetteer(files[i]);
      hashNames[i] = files[i].getName();
    }
  }
  
  //transform applied to all gazetteer entries
  private String t(String s) {
    return s.toLowerCase().replaceAll("\\p{P}+", " ").replaceAll("  +", " ").trim();
  }
  
  public static boolean withinLength(String s) {
    int ct = 0;
    s = s.trim();
    int idx = s.indexOf(" ");
    while(idx >=0) {
      if(++ct == MAXLENGTH) {
        return false;
      }
      idx = s.indexOf(" ", idx+1);
    }
    return true;
  }
  
  private LongHashSet readGazetteer(File f) throws IOException {
    BufferedReader brIn = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
    String sLine;
    LongHashSet out = new LongHashSet();
    while((sLine = brIn.readLine())!=null) {
      if(sLine.startsWith("#")||sLine.trim().length()==0)
        continue;
      if(!withinLength(sLine))
        continue;
      out.add(StringLongHash.hash(t(sLine)));
    }
    brIn.close();
    return out;
  }
  
  public int size() {
    return hashSets.length;
  }
  
  public int sizeOfSet(int set) {
    return hashSets[set].size();
  }
  
  public boolean inSet(String s, int i) {
    long hc = StringLongHash.hash(t(s));
    return hashSets[i].contains(hc);
  }
  
  //returns whether a string is in each gazetteer
  public boolean [] inSet(String s) {
    long hc = StringLongHash.hash(t(s));
    boolean [] out = new boolean[hashSets.length];
    Arrays.fill(out, false);
    for(int i=0; i<hashSets.length;i++) {
      if(hashSets[i].contains(hc))
        out[i] = true;
    }
    return out;
  }
  
  //-1 if not found
  public int gazetteerNumber(String s) {
    for(int i=0;i<=hashSets.length;i++) {
      if(s.equals(hashNames[i]))
        return i;
    }
    return -1;
  }
  
  public String getStringSpan(List<String> ws, int start, int length) {
    StringBuffer sb = new StringBuffer();
    for(int i=start;i<length+start; i++) {
       sb.append(ws.get(i) + " ");
    }
    return sb.toString().trim();
  }
  
  public List<LabelSpan> getSpansForGaz(List<String> ws, int gn) {
    ArrayList<LabelSpan> out = new ArrayList<>();
    for(int i=0; i<ws.size();i++) {
      for(int j=0; j<Math.min(MAXLENGTH, ws.size()+1-i); j++) {
        String query = getStringSpan(ws, i, j);
        if(inSet(query, gn)) {
          LabelSpan ls = new LabelSpan(hashNames[gn], Tuples.pair(i, i+j));
          out.add(ls);
        }
      }
    }
    return out;
  }
  
  public List<LabelSpan> getSpans(List<String> ws) {
    ArrayList<LabelSpan> out = new ArrayList<>();
    for(int i=0; i<this.hashSets.length; i++) {
      out.addAll(getSpansForGaz(ws, i));
    }
    return out;
  }
  
}
