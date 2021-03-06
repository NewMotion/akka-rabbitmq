import com.typesafe.sbt.SbtScalariform._
import scalariform.formatter.preferences._

object Format {
  lazy val settings = Seq(
    ScalariformKeys.autoformat := true,
    ScalariformKeys.preferences := formattingPreferences
  )

  val formattingPreferences = FormattingPreferences()
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentConstructorArguments, false)
}
