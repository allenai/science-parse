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

In JSON format, the [output looks like this](http://scienceparse.allenai.org/v1/498bb0efad6ec15dd09d941fb309aa18d6df9f5f) (or like [this, if you want sections](http://scienceparse.allenai.org/v1/498bb0efad6ec15dd09d941fb309aa18d6df9f5f?skipFields=sections)). The easiest way to get started is to use the output from this server.

## Get started
There are three different ways to get started with SP. Each has its own document:

 * [Server](server/README.md): This contains the SP server. It's useful for PDF parsing as a service. It's also probably the easiest way to get going.
 * [CLI](cli/README.md): This contains the command line interface to SP. That's most useful for batch processing.
 * [Core](core/README.md): This contains SP as a library. It has all the extraction code, plus training and evaluation. Both server and CLI use this to do the actual work.

Alternatively, you can run the **docker image**: `docker run -p 8080:8080 --rm allenai-docker-public-docker.bintray.io/s2/scienceparse:1.2.7-SNAPSHOT`

## How to include into your own project
 
The current version is `1.2.6`. If you want to include it in your own project, use this:

For SBT:
```
libraryDependencies += "org.allenai" %% "science-parse" % "1.2.6"
```

For Maven:
```
<dependency>
  <groupId>org.allenai</groupId>
  <artifactId>science-parse_2.11</artifactId>
  <version>1.2.6</version>
  <type>pom</type>
</dependency>
```

The first time you run it, SP will download some rather large model files. Don't be alarmed! The model files are cached, and startup is much faster the second time.

For licensing reasons, SP does not include libraries for some image formats. Without these
libraries, SP cannot process PDFs that contain images in these formats. If you have no
licensing restrictions in your project, we recommend you add these additional dependencies to your
project as well:
```
  "com.github.jai-imageio" % "jai-imageio-core" % "1.2.1",
  "com.github.jai-imageio" % "jai-imageio-jpeg2000" % "1.3.0", // For handling jpeg2000 images
  "com.levigo.jbig2" % "levigo-jbig2-imageio" % "1.6.5", // For handling jbig2 images
```

## Development

This project is a hybrid between Java and Scala. The interaction between the languages is fairly seamless, and SP can be used as a library in any JVM-based language.

### Lombok

This project uses [Lombok](https://projectlombok.org) which requires you to enable annotation processing inside of an IDE.
[Here](https://plugins.jetbrains.com/plugin/6317) is the IntelliJ plugin and you'll need to enable annotation processing (instructions [here](https://www.jetbrains.com/idea/help/configuring-annotation-processing.html)).

Lombok has a lot of useful annotations that give you some of the nice things in Scala:

* `val` is equivalent to `final` and the right-hand-side class. It gives you type-inference via some tricks
* Check out [`@Data`](https://projectlombok.org/features/Data.html)

## Thanks

Special thanks goes to @kermitt2, whose work on [kermitt2/grobid](https://github.com/kermitt2/grobid) inspired Science Parse, and helped us get started with some labeled data.
