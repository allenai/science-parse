import org.allenai.plugins.Ai2ReleasePlugin

import sbt.{ AutoPlugin, Plugins }

object LibraryPluginLight extends AutoPlugin {
  override def requires: Plugins = CoreSettingsPluginLight// && Ai2ReleasePlugin // DEBUG
}
