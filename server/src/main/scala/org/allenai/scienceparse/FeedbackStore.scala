package org.allenai.scienceparse

import com.typesafe.config.{ConfigFactory, Config}
import org.allenai.common.{Resource, Logging}
import org.allenai.common.Config._

import scalikejdbc._

import java.time.Instant

object FeedbackStore extends Logging {
  { // Set up the DB
    Class.forName("org.postgresql.Driver")

    val config = ConfigFactory.load()
    val dbConfig: Config = config[Config]("org.allenai.scienceparse.Server.db")

    scalikejdbc.GlobalSettings.loggingSQLAndTime = scalikejdbc.LoggingSQLAndTimeSettings(
      enabled = dbConfig.get[Boolean]("logging").getOrElse(false),
      logLevel = 'DEBUG,
      warningEnabled = true,
      warningThresholdMillis = 1000L,
      warningLogLevel = 'WARN
    )

    val dbUrl = dbConfig.getString("url")
    val dbUser = dbConfig.getString("user")
    val dbPassword = dbConfig.get[String]("password").getOrElse(
      throw new IllegalArgumentException("Password for DB not set. Please set org.allenai.scienceparse.Server.db.password."))
    ConnectionPool.singleton(dbUrl, dbUser, dbPassword)

    // upgrade the schema if necessary
    val dbRootConfig: Config = config[Config]("org.allenai.scienceparse.Server.db-as-root")
    if (dbRootConfig[Boolean]("upgradeSchema")){
      val dbUrl = dbRootConfig.getString("url")
      logger.info(s"Connecting to $dbUrl")
      val dbUser = dbRootConfig.getString("user")
      val dbPassword = dbRootConfig.get[String]("password").getOrElse(
        throw new IllegalArgumentException("Root password for DB not set. Please set org.allenai.scienceparse.Server.db-as-root.password."))

      val rootConnectionPoolName = "rootConnectionPool"
      val cpSettings = new ConnectionPoolSettings(initialSize = 1, maxSize = 2)
      ConnectionPool.add(rootConnectionPoolName, dbUrl, dbUser, dbPassword, cpSettings)
      Resource.using(ConnectionPool(rootConnectionPoolName)) { implicit cp =>
        DB.localTx { implicit session =>
          sql"""
            CREATE TABLE IF NOT EXISTS settings (
              key VARCHAR NOT NULL PRIMARY KEY,
              value VARCHAR NOT NULL)
          """.execute().apply()

          def dbSchemaVersion =
            sql"SELECT value::integer FROM settings WHERE key = 'version'".map(_.int("value")).single().apply().getOrElse(0)
          val desiredSchemaVersion = 1
          val schemaUpdateFunctions = Map(
            0 -> (() => {
              sql"""
                CREATE TABLE feedback (
                  paperId CHAR(40) NOT NULL,
                  timeAdded TIMESTAMP NOT NULL,
                  value JSONB NOT NULL,
                  PRIMARY KEY(paperId, timeAdded))
              """.execute().apply()

              sql"""
                INSERT INTO settings (key, value) VALUES ('version', 1)
              """.execute().apply()
            })
          )

          var currentSchemaVersion = dbSchemaVersion
          while(currentSchemaVersion != desiredSchemaVersion) {
            val updateFunction = schemaUpdateFunctions.getOrElse(
              currentSchemaVersion,
              throw new RuntimeException(s"Could not find upgrade function for version $currentSchemaVersion."))
            updateFunction()

            val newSchemaVersion = dbSchemaVersion
            if(newSchemaVersion == currentSchemaVersion)
              throw new RuntimeException(s"Upgrade function for version $currentSchemaVersion did not change the version.")
            currentSchemaVersion = newSchemaVersion
          }
        }
      }
    }
  }

  def addFeedback(paperId: String, data: LabeledData): Unit = {
    import spray.json._
    import LabeledDataJsonProtocol._

    val jsonString = data.toJson.compactPrint
    DB.localTx { implicit t =>
      sql"""
        INSERT INTO feedback (paperId, timeAdded, value) VALUES
        ($paperId, current_timestamp, $jsonString::jsonb)
      """.update().apply()
    }
  }

  private val paperSource = PaperSource.getDefault

  def getFeedback(paperId: String): Option[LabeledData] = {
    import spray.json._
    import LabeledDataJsonProtocol._

    DB.readOnly { implicit t =>
      sql"""
        SELECT value FROM feedback WHERE paperId=$paperId ORDER BY timeAdded DESC LIMIT 1
      """.map { result =>
        val jsonString = result.string("value")
        jsonString.parseJson.convertTo[LabeledData]
      }.first().apply()
    }
  }

  /**
    * @param onOrAfter If given, constrains returned feedback to those added on or after this timestamp.
    * @param before If given, constrains returned feedback to those added before this timestamp.
    */
  def getAllFeedback(
    onOrAfter: Option[Instant] = None,
    before: Option[Instant] = None
  ): Traversable[(String, LabeledData)] = {
    import spray.json._
    import LabeledDataJsonProtocol._

    val onOrAfterClause = onOrAfter.map(ts => sqls" AND a.timeadded >= $ts").getOrElse(sqls"")
    val beforeClause = before.map(ts => sqls" AND a.timeadded < $ts").getOrElse(sqls"")

    DB.readOnly { implicit t =>
      sql"""
        SELECT a.paperId AS paperId, a.value AS value FROM feedback AS a JOIN (
          SELECT paperId, MAX(timeAdded) AS timeAdded FROM feedback GROUP BY paperId
        ) AS b ON a.paperId = b.paperId AND a.timeAdded = b.timeAdded
        $onOrAfterClause $beforeClause
      """.map { result =>
        val paperId = result.string("paperId")
        val jsonString = result.string("value")
        (paperId, jsonString.parseJson.convertTo[LabeledData])
      }.traversable.apply()
    }
  }
}