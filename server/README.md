# Science Parse Server

This is a wrapper that makes the [SP library](../core/README.md) available as a web service. We have a version running at http://scienceparse.allenai.org, so you can try it yourself:
```
curl "http://scienceparse.allenai.org/v1/498bb0efad6ec15dd09d941fb309aa18d6df9f5f"
```
This will print a large amount of JSON. Most of it is body text. You can get a slightly more compact output by skipping the body text:
```
curl "http://scienceparse.allenai.org/v1/498bb0efad6ec15dd09d941fb309aa18d6df9f5f?skipFields=sections"
```

Both of these examples parse the paper with the S2 paper id `498bb0efad6ec15dd09d941fb309aa18d6df9f5f`. You can see that paper here: https://pdfs.semanticscholar.org/498b/b0efad6ec15dd09d941fb309aa18d6df9f5f.pdf
