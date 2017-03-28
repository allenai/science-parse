# Science Parse Server

This is a wrapper that makes the [SP library](../core/README.md) available as a web service. We have a version running at http://scienceparse.allenai.org, so you can try it yourself: http://scienceparse.allenai.org/v1/498bb0efad6ec15dd09d941fb309aa18d6df9f5f

This will show a large amount of JSON. Most of it is body text. You can get a slightly more compact output by skipping the body text: http://scienceparse.allenai.org/v1/498bb0efad6ec15dd09d941fb309aa18d6df9f5f?skipFields=sections

Both of these examples parse the paper with the S2 paper id `498bb0efad6ec15dd09d941fb309aa18d6df9f5f`. You can see that paper here: https://pdfs.semanticscholar.org/498b/b0efad6ec15dd09d941fb309aa18d6df9f5f.pdf

## Parsing your own PDF

If you want to upload your own PDF, you can do that too, with a HTTP POST:
```
curl -v -H "Content-type: application/pdf" --data-binary @paper.pdf "http://scienceparseallenai.org/v1
```

Note that the content type needs to be `application/pdf`, and the URL needs to not have a trailing slash.

## Running the server yourself

You can compile the server into a super-jar with sbt like this `sbt server/assembly`. That will download all dependencies, compile them, and build an executable jar with all dependencies bundled. Then, you can start up the server with `java -jar jarfile.jar`. On first startup, it will download several gigabytes of model files, and then bind to port 8080 on the machine you run it on.

The server takes a few command line arguments. Run it with `java -jar jarfile.jar --help` to see what they are.
