package com.prisma.api.connector.mysql.database

import com.prisma.gc_values._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.{PositionedParameters, SQLActionBuilder, SetParameter}
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.json.{JsValue => SprayJsValue}
import play.api.libs.json.{Json, JsValue => PlayJsValue}
import JdbcExtensions._
import com.prisma.api.connector.Types.DataItemFilterCollection
import com.prisma.shared.models.Model

object SlickExtensions {

  implicit object SetGcValueParam extends SetParameter[GCValue] {
    override def apply(gcValue: GCValue, pp: PositionedParameters): Unit = {
      val npos = pp.pos + 1
      pp.ps.setGcValue(npos, gcValue)
      pp.pos = npos
    }
  }

  implicit class SQLActionBuilderConcat(val a: SQLActionBuilder) extends AnyVal {
    def concat(b: SQLActionBuilder): SQLActionBuilder = {
      SQLActionBuilder(a.queryParts ++ " " ++ b.queryParts, (p: Unit, pp: PositionedParameters) => {
        a.unitPConv.apply(p, pp)
        b.unitPConv.apply(p, pp)
      })
    }
    def concat(b: Option[SQLActionBuilder]): SQLActionBuilder = b match {
      case Some(b) => a concat b
      case None    => a
    }

    def ++(b: SQLActionBuilder): SQLActionBuilder         = concat(b)
    def ++(b: Option[SQLActionBuilder]): SQLActionBuilder = concat(b)
  }

  def listToJson(param: List[Any]): String =
    param
      .map {
        case v: String     => v.toJson
        case v: JsValue    => v.toJson
        case v: Boolean    => v.toJson
        case v: Int        => v.toJson
        case v: Long       => v.toJson
        case v: Float      => v.toJson
        case v: Double     => v.toJson
        case v: BigInt     => v.toJson
        case v: BigDecimal => v.toJson
        case v: DateTime   => v.toString.toJson
      }
      .toJson
      .toString

  def escapeUnsafeParam(param: Any): SQLActionBuilder = {
    def unwrapSome(x: Any): Any = x match {
      case Some(x) => x
      case x       => x
    }

    unwrapSome(param) match {
      case param: String       => sql"$param"
      case param: PlayJsValue  => sql"${param.toString}"
      case param: SprayJsValue => sql"${param.compactPrint}"
      case param: Boolean      => sql"$param"
      case param: Int          => sql"$param"
      case param: Long         => sql"$param"
      case param: Float        => sql"$param"
      case param: Double       => sql"$param"
      case param: BigInt       => sql"#${param.toString}"
      case param: BigDecimal   => sql"#${param.toString}"
      case param: DateTime     => sql"${param.toString(DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZoneUTC())}"
      case param: Vector[_]    => sql"${listToJson(param.toList)}"
      case None                => sql"NULL"
      case null                => sql"NULL"
      case _                   => throw new IllegalArgumentException("Unsupported scalar value in SlickExtensions: " + param.toString)
    }
  }

  def gcValueToSQLBuilder(value: GCValue): SQLActionBuilder = {
    val dateTimeFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZoneUTC()
    value match {
      case NullGCValue        => sql"NULL"
      case x: StringGCValue   => sql"${x.value}"
      case x: EnumGCValue     => sql"${x.value}"
      case x: IdGCValue       => sql"${x.value}"
      case x: DateTimeGCValue => sql"${dateTimeFormat.print(x.value)}"
      case x: IntGCValue      => sql"${x.value}"
      case x: FloatGCValue    => sql"${x.value}"
      case x: BooleanGCValue  => sql"${x.value}"
      case x: JsonGCValue     => sql"${x.value.toString}"
      case x: ListGCValue     => sys.error("ListGCValue not implemented here yet.")
      case x: RootGCValue     => sys.error("RootGCValues not implemented here yet.")
    }
  }

  def listToJsonList(param: List[Any]): String = {
    val x = listToJson(param)
    x.substring(1, x.length - 1)
  }

  def escapeUnsafeParamListValue(param: Vector[Any]) = sql"${listToJsonList(param.toList)}"

  def escapeKey(key: String) = sql"`#$key`"

  def combineByAnd(actions: Iterable[SQLActionBuilder]) = generateParentheses(combineBy(actions, "and"))

  def combineByOr(actions: Iterable[SQLActionBuilder]) = generateParentheses(combineBy(actions, "or"))

  def combineByComma(actions: Iterable[SQLActionBuilder]) = combineBy(actions, ",")

  def generateParentheses(sql: Option[SQLActionBuilder]) = sql match {
    case None      => None
    case Some(sql) => Some(sql"(" concat sql concat sql")")
  }

  // Use this with caution, since combinator is not escaped!
  def combineBy(actions: Iterable[SQLActionBuilder], combinator: String): Option[SQLActionBuilder] = actions.toList match {
    case Nil         => None
    case head :: Nil => Some(head)
    case _           => Some(actions.reduceLeft((a, b) => a concat sql"#$combinator" concat b))
  }

  def prefixIfNotNone(prefix: String, action: Option[SQLActionBuilder]): Option[SQLActionBuilder] = {
    if (action.isEmpty) None else Some(sql"#$prefix " concat action.get)
  }

  def whereFilterAppendix(projectId: String, model: Model, filter: Option[DataItemFilterCollection]) = {
    val whereSql = filter.flatMap(where => QueryArgumentsHelpers.generateFilterConditions(projectId, model.name, where))
    prefixIfNotNone("WHERE", whereSql)
  }
}
