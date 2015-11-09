package org.allenai.scienceparse;

import lombok.Data;

@Data
public class CitationRecord {
	public final int startOffset;
	public final int endOffset;
	public final int referenceID;
}
