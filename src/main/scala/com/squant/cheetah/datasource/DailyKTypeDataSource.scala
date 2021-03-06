package com.squant.cheetah.datasource

import java.io.{File, FileWriter}
import java.time.LocalDateTime

import com.squant.cheetah.Feeds
import com.squant.cheetah.domain._
import com.squant.cheetah.engine.DataBase
import com.squant.cheetah.utils.Constants._
import com.squant.cheetah.utils._
import com.typesafe.scalalogging.LazyLogging

import scala.collection._
import scala.io.Source

object DailyKTypeDataSource extends DataSource with LazyLogging {

  private val baseDir = config.getString(CONFIG_PATH_DB_BASE)
  private val ktypeDir = config.getString(CONFIG_PATH_KTYPE)

  private val indexURL = "http://quotes.money.163.com/service/chddata.html?code=%s&start=%s&end=%s&fields=TCLOSE;HIGH;LOW;TOPEN;LCLOSE;CHG;PCHG;VOTURNOVER;VATURNOVER"
  private val stockURL = "http://quotes.money.163.com/service/chddata.html?code=%s&start=%s&end=%s&fields=TCLOSE;HIGH;LOW;TOPEN;LCLOSE;CHG;PCHG;TURNOVER;VOTURNOVER;VATURNOVER;TCAP;MCAP"

  private val INDEX_SYMBOL = Map[String, String](
    ("000001", "0000001"), ("000002", "0000002"), ("000003", "0000003"), ("000008", "0000008"),
    ("000009", "0000009"), ("000010", "0000010"), ("000011", "0000011"), ("000012", "0000012"),
    ("000016", "0000016"), ("000017", "0000017"), ("000300", "0000300"), ("399001", "1399001"),
    ("399002", "1399002"), ("399003", "1399003"), ("399004", "1399004"), ("399005", "1399005"), ("399006", "1399006"),
    ("399100", "1399100"), ("399101", "1399101"), ("399106", "1399106"), ("399107", "1399107"), ("399108", "1399108"),
    ("399333", "1399333"), ("399606", "1399606")
  )

  //初始化数据源
  override def init(taskConfig: TaskConfig =
                    TaskConfig("DailyKTypeDataSource",
                      "", true, true, false,
                      LocalDateTime.of(1990, 1, 1, 0, 0), LocalDateTime.now)): Unit = {
    clear()
    update(taskConfig)
  }

  //每个周期更新数据
  override def update(taskConfig: TaskConfig): Unit = {
    def stockCode(code: String): String = {
      if (code.length != 6)
        return ""
      else {
        val index_code = if (List("5", "6", "9").contains(String.valueOf(code.charAt(0)))) s"0$code" else s"1$code"
        return index_code
      }
    }

    if (taskConfig.clear) clear()

    logger.info(s"Start to download index daily bar data, ${format(taskConfig.stop, "yyyyMMdd")}")
    //update index daily data
    for ((code, rCode) <- INDEX_SYMBOL) {
      val data = Source.fromURL(indexURL.format(rCode, format(taskConfig.start, "yyyyMMdd"),
        format(taskConfig.stop, "yyyyMMdd")), "gbk").getLines()
      if (taskConfig.toCSV) toCSV(code, data, "index")
      if (taskConfig.toDB) toDB(code, true)
    }
    logger.info(s"Download completed")
    logger.info(s"Start to download stock daily bar data, ${format(taskConfig.stop, "yyyyMMdd")}")
    //update stock daily data
    val stocks = Feeds.symbols()
    stocks.par.map{ stock =>
      val data = Source.fromURL(stockURL.format(stockCode(stock.code), format(taskConfig.start, "yyyyMMdd"),
        format(taskConfig.stop, "yyyyMMdd")), "gbk").getLines()
      if (taskConfig.toCSV) toCSV(stock.code, data, "stock")
      if (taskConfig.toDB) toDB(stock.code, false)
    }
    logger.info(s"Download completed")
  }

  def toCSV(code: String, data: Iterator[String], path: String): Unit = {
    val file = new File(s"$baseDir/$ktypeDir/day/$path/$code.csv")

    val dir = new File(s"$baseDir/$ktypeDir/day/$path")
    if (!dir.exists()) {
      dir.mkdirs()
    }
    val isNewFile = !file.exists();

    val writer = new FileWriter(file, true)

    if (isNewFile) {
      writer.write(new String(data.next().getBytes("utf-8")) + "\n")
    }
    val iter = data.drop(1).toList.reverse
    for (line: String <- iter) {
      writer.write(new String(line.getBytes("utf-8")) + "\n")
    }
    writer.close()
  }

  /**
    *
    * @param code
    * @param index if true,return index data ,else return stock data
    * @return
    */
  def fromCSV(code: String, index: Boolean = false): List[Bar] = {

    val baseDir = config.getString(CONFIG_PATH_DB_BASE)
    val ktypeDir = config.getString(CONFIG_PATH_KTYPE)

    def mapToStock(map: Map[String, String]): Bar = new Bar(
      DAY,
      map.get("date").get,
      map.get("code").get,
      map.get("name").getOrElse(""),
      map.get("close").getOrElse("0").toFloat,
      map.get("high").getOrElse("0").toFloat,
      map.get("low").getOrElse("0").toFloat,
      map.get("open").getOrElse("0").toFloat,
      map.get("lastClose").getOrElse("0").toFloat,
      map.get("p_change").getOrElse("0").toFloat,
      map.get("a_change").getOrElse("0").toFloat,
      map.get("volume").getOrElse("0").toFloat,
      map.get("amount").getOrElse("0").toFloat,
      map.get("turnover").getOrElse("0").toFloat,
      map.get("mktcap").getOrElse("0").toFloat,
      map.get("nmc").getOrElse("0").toFloat
    )

    var file: String = ""
    if (index) {
      file = s"$baseDir$ktypeDir/day/index/$code.csv"
    } else {
      file = s"$baseDir$ktypeDir/day/stock/$code.csv"
    }
    val lines = scala.io.Source.fromFile(new File(file)).getLines().toList.drop(1)
    val columns = if (index) dayIndexColumns else dayStockColumns
    for {
      line <- lines
      fields = line.replaceAll("None", "0").split(",")
      if (fields.length == 15 || fields.length == 12 || fields.length == 7)
      map = (columns zip fields) (breakOut): Map[String, String]
    } yield mapToStock(map)
  }

  def getTableName(code: String, index: Boolean = false): String = {
    val path = index match {
      case true => "index"
      case false => "stock"
    }
    s"ktype_day_${code}_${path}"
  }

  def toDB(code: String, index: Boolean = false): Unit = {
    val bars = fromCSV(code, index)
    if (bars != null && bars.size > 0)
      DataBase.getEngine.toDB(getTableName(code, index), bars.toList.map(Bar.barToRow))
  }

  def fromDB(code: String, index: Boolean = false, start: LocalDateTime, stop: LocalDateTime = LocalDateTime.now): List[Bar] = {
    DataBase.getEngine.fromDB(getTableName(code, index), start, stop).map(Bar.rowToBar)
  }

  //清空数据源
  override def clear(): Unit = {
    rm(s"/$baseDir/$ktypeDir/day").foreach(r => logger.info(s"delete ${r._1} ${r._2}"))
  }

  def typeToPath(barType: BarType): String = {
    val ktype = Map("MIN_5" -> "5", "MIN_15" -> "15", "MIN_30" -> "30", "MIN_60" -> "60", "DAY" -> "day", "WEEK" -> "week", "MONTH" -> "month")
    ktype.get(barType.toString).get
  }
}