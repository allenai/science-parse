import org.scalatest.testng.TestNGWrapperSuite

class TestRunner extends TestNGWrapperSuite (
  List("src/test/resources/testng.xml")
)

