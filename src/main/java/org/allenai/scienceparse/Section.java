package org.allenai.scienceparse;

import lombok.Data;

@Data
public class Section {
  public Section(final String heading, final String text) {
    this.heading = heading;
    this.text = text;
  }

  public String heading;
  public String text;
}
