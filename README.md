# Science Parse

Parsing PDF meta-data.

## Getting Started

This project is currently Java 8 built with `gradle`. To install `gradle` simply install via `brew install gradle` via [Homebrew](http://brew.sh). Then if you can do:
```bash
> gradle test # Run unit tests
> gradle idea # Generate IntelliJ project
```

### Lombok

This project uses [Lombok](https://projectlombok.org) which requires you to enable annotation processing inside of an IDE.
[Here](https://plugins.jetbrains.com/plugin/6317) is the IntelliJ plugin and you'll need to enable annotation processing (instructions [here](https://www.jetbrains.com/idea/help/configuring-annotation-processing.html)).

Lombok has a lot of useful annotations that give you some of the nice things in Scala:

* `val` is equivalent to `final` and the right-hand-side class. It gives you type-inference via some tricks
* Checkout [`@Data`](https://projectlombok.org/features/Data.html)

