package org.allenai.scienceparse;

import java.util.List;

import lombok.Data;

@Data
public class BibRecord {
	public final String title;
	public final List<String> author;
	public final String venue;
}
