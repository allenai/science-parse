package org.allenai.scienceparse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import junit.framework.Assert;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Test
@Slf4j
public class ExtractReferencesTest {
	  public String filePathOfResource(String path) {
		    return this.getClass().getResource(path).getFile();
	}
	  
	  public String resourceDirectory(String path) {
		  return (new File(this.getClass().getResource(path).getFile())).getParent();
	  }
	
	public void testFindReferences() throws Exception {
		String ans = "[1] E. Chang and A. Zakhor, “Scalable video data placement on parallel disk "
				+ "arrays,” in IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, "
				+ "Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208–221.";		
		File paper = new File(filePathOfResource("/4230b5328df3f8125da9b84a82d92b46a240.pdf"));
		//File paper = new File("e:\\data\\science-parse\\qtest\\aaai04.pdf");

		Parser.ParseOpts opts = new Parser.ParseOpts();
	  	opts.iterations = 10;
	  	opts.threads = 4;
	  	opts.modelFile = "src/test/resources/test.model";
	  	opts.headerMax = Parser.MAXHEADERWORDS;
	  	opts.backgroundSamples = 3;
	  	opts.gazetteerFile = null;
	  	opts.trainFraction = 0.9;
	  	opts.backgroundDirectory = filePathOfResource("/groundTruth.json");
	  	opts.minYear = -1;
	  	opts.checkAuthors = false;

	  	File f = new File(opts.modelFile);
	  	f.deleteOnExit();
	  	ParserGroundTruth pgt = new ParserGroundTruth(filePathOfResource("/groundTruth.json"));
		Parser.trainParser(null, pgt, resourceDirectory("/groundTruth.json"), opts, null); //assumes pdfs in same dir as groundTruth
		Parser p = new Parser(opts.modelFile);
		
		val fis = new FileInputStream(paper);
		ExtractedMetadata em = null;
		try {
			em = p.doParse(fis, Parser.MAXHEADERWORDS);
		}
		catch(Exception e) {
			log.info("Parse error: " + f);
			//e.printStackTrace();
		}
		fis.close();
		List<BibRecord> br = ExtractReferences.findReferences(em.raw);
		int j=0;
		for(BibRecord b : br)
			log.info("reference " + (j++) + " " + (b==null?"null":b.toString()));
		for(BibRecord b : br)
			Assert.assertNotNull(b);
		Assert.assertEquals(17, br.size());
		Assert.assertEquals("Scalable video data placement on parallel disk arrays", br.get(0).title);
		Assert.assertEquals("1", br.get(0).citeStr);
		Assert.assertEquals("E. Chang", br.get(0).author.get(0));
		Assert.assertEquals("A. Zakhor", br.get(0).author.get(1));
		Assert.assertEquals(1994, br.get(0).year);
		Assert.assertTrue(br.get(0).venue.trim().startsWith("IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208"));
		//can't use below because dash is special:
//		Assert.assertEquals("IS&T/SPIE Int. Symp. Electronic Imaging: Science and Technology, "
//				+ "Volume 2185: Image and Video Databases II, San Jose, CA, Feb. 1994, pp. 208–221.", br.get(0).venue.trim());
		
	}
	
}