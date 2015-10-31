This is a fork of the [science-parse](https://github.com/allenai/science-parse) project. It uses our local build of [pdfbox](http://utility.allenai.org:8081/nexus/content/repositories/thirdparty/org/apache/pdfbox-local/). It also uses sbt to build instead of gradle.

# Science Parse

Parsing PDF meta-data.

### Lombok

This project uses [Lombok](https://projectlombok.org) which requires you to enable annotation processing inside of an IDE.
[Here](https://plugins.jetbrains.com/plugin/6317) is the IntelliJ plugin and you'll need to enable annotation processing (instructions [here](https://www.jetbrains.com/idea/help/configuring-annotation-processing.html)).

Lombok has a lot of useful annotations that give you some of the nice things in Scala:

* `val` is equivalent to `final` and the right-hand-side class. It gives you type-inference via some tricks
* Check out [`@Data`](https://projectlombok.org/features/Data.html)

