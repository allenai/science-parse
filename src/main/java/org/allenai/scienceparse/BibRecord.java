package org.allenai.scienceparse;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

  static private String normalizeInitialsInAuthor(String author) {
    author = author.replaceAll("(\\p{Lu}\\.) (\\p{Lu}\\.)", "$1$2");
    author = author.replaceAll("(\\p{Lu}\\.) (\\p{Lu}\\.)", "$1$2"); //twice to catch three-initial seq.
    return author;
  }

  public BibRecord withNormalizedAuthors() {
    return new BibRecord(
            title,
            author.stream().map(BibRecord::normalizeInitialsInAuthor).collect(Collectors.toList()),
            venue,
            citeRegEx,
            shortCiteRegEx,
            year);
  }

  public String title;
  public final List<String> author;
  public final String venue;
  public final Pattern citeRegEx;
  public final Pattern shortCiteRegEx;
  public final int year;
}
