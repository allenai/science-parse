# Science Parse

Science Parse parses scientific papers (in PDF form) and returns them in structured form. As of today, it supports these fields:
 * Title
 * Authors
 * Abstract
 * Sections (each with heading and body text)
 * Bibliography, each with
   * Title
   * Authors
   * Venue
   * Year
 * Mentions, i.e., places in the paper where bibliography entries are mentioned

The project has three parts, each with their own README.md:
 * Core: This contains SP as a library. It has all the extraction code, plus training and evaluation.
 * Server: This contains the SP server. It's useful for PDF parsing as a service.
 * CLI: This contains the command line interface to SP. That's most useful for batch processing.
 
# Development

This project is a hybrid between Java and Scala. The interaction between the languages is fairly seamless, and SP can be used as a library in an JVM-based language.

### Lombok

This project uses [Lombok](https://projectlombok.org) which requires you to enable annotation processing inside of an IDE.
[Here](https://plugins.jetbrains.com/plugin/6317) is the IntelliJ plugin and you'll need to enable annotation processing (instructions [here](https://www.jetbrains.com/idea/help/configuring-annotation-processing.html)).

Lombok has a lot of useful annotations that give you some of the nice things in Scala:

* `val` is equivalent to `final` and the right-hand-side class. It gives you type-inference via some tricks
* Check out [`@Data`](https://projectlombok.org/features/Data.html)
