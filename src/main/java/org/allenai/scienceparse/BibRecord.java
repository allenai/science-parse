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

  // Something is wrong with sbt, lombok, and Scala/Java interop, making this unconstructable from
  // Scala if you don't write this custom constructor.
  public BibRecord(
          final String title,
          final List<String> author,
          final String venue,
          final Pattern citeRegEx,
          final Pattern shortCiteRegEx,
          final int year
  ) {
    this.title = title;
    this.author = author;
    this.venue = venue;
    this.citeRegEx = citeRegEx;
    this.shortCiteRegEx = shortCiteRegEx;
    this.year = year;
  }

  public final String title;
  public final List<String> author;
  public final String venue;
  public final Pattern citeRegEx;
  public final Pattern shortCiteRegEx;
  public final int year;
}
