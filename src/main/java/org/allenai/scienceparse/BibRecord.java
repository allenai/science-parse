package org.allenai.scienceparse;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;

@Data @EqualsAndHashCode(exclude={"citeRegEx", "shortCiteRegEx"})
public class BibRecord {
  public static final int MINYEAR = 1800;
  public static final int MAXYEAR = Calendar.getInstance().get(Calendar.YEAR) + 10;

  public final String title;
  public final List<String> author;
  public final String venue;
  public final Pattern citeRegEx;
  public final Pattern shortCiteRegEx;
  public final int year;
}
