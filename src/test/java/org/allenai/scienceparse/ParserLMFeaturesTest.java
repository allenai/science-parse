package org.allenai.scienceparse;

import java.io.File;

import org.testng.annotations.Test;

import junit.framework.Assert;
import lombok.extern.slf4j.Slf4j;

@Test
@Slf4j
public class ParserLMFeaturesTest {

	  public String filePathOfResource(String path) {
		    return this.getClass().getResource(path).getFile();
	}
	
	public void testParserLMFeatures() throws Exception {
		File f = new File(filePathOfResource("/groundTruth.json"));
		ParserGroundTruth pgt = new ParserGroundTruth(f.getPath());
		log.info("pgt 0: " + pgt.papers.get(0));
		ParserLMFeatures plf = new ParserLMFeatures(pgt.papers, 0, pgt.papers.size(), f.getParentFile(), 3);
		log.info("of count in background: " + plf.backgroundBow.get("of"));
		Assert.assertEquals(1.0, plf.authorBow.get("Seebode"));
		Assert.assertEquals(1.0, plf.titleBow.get("Disk-based"));
		Assert.assertTrue(plf.backgroundBow.get("of") > 2.0);
	}
	
}
