/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin.sqlserver.destination

import quasar.plugin.sqlserver._

import scala._, Predef._

import java.io.InputStream

import cats.data.NonEmptyList
import cats.effect.ConcurrentEffect

import com.microsoft.sqlserver.jdbc.{
  SQLServerBulkCopy,
  SQLServerBulkCopyOptions,
  SQLServerBulkCSVFileRecord,
  SQLServerConnection
}

import doobie._
import doobie.enum.JdbcType
import doobie.implicits._

import fs2.{Pipe, Stream}

import org.slf4s.Logger

import quasar.plugin.jdbc.Slf4sLogHandler
import quasar.plugin.jdbc.destination.WriteMode

private[destination] object CsvCreateSink {
  def apply[F[_]: ConcurrentEffect](
      writeMode: WriteMode,
      xa: Transactor[F],
      logger: Logger)(
      obj: Either[HI, (HI, HI)],
      cols: NonEmptyList[(HI, SQLServerType)])
      : Pipe[F, Byte, Unit] = {

    val logHandler = Slf4sLogHandler(logger)

    val objFragment: Fragment = obj.fold(
      t => Fragment.const0(t.forSql),
      { case (d, t) => Fragment.const0(d.forSql) ++ fr0"." ++ Fragment.const0(t.forSql) })

    val unsafeObj: String = obj.fold(
      t => t.unsafeString,
      { case (d, t) => d.unsafeString ++ "." ++ t.unsafeString })

    val unsafeTableName: String = obj.fold(
      t => t.unsafeString,
      { case (_, t) => t.unsafeString })

    val unsafeTableSchema: Option[String] = obj.fold(
      _ => None,
      { case (d, _) => Some(d.unsafeString) })

    def loadCsv(bytes: InputStream, connection: java.sql.Connection): F[Unit] = {
      type Utilities = (SQLServerBulkCopy, SQLServerBulkCSVFileRecord, SQLServerBulkCopyOptions)

      def acquire: F[Utilities] = ConcurrentEffect[F] delay {
        val bulkCopy = new SQLServerBulkCopy(connection)
        val fileRecord = new SQLServerBulkCSVFileRecord(bytes, "UTF-8", ",", false)
        val copyOptions = new SQLServerBulkCopyOptions()

        (bulkCopy, fileRecord, copyOptions)
      }

      def use: Utilities => F[Unit] = {
        case (bulkCopy, fileRecord, copyOptions) => ConcurrentEffect[F] delay {
          cols.zipWithIndex.toList foreach {
            case ((_, tpe), idx) =>
              val (sqlTpe, precision, scale) = toJdbcType(tpe)
              fileRecord.addColumnMetadata(idx + 1, "", sqlTpe.toInt, precision, scale)
          }
          logger.debug(s"Added column metadata for $unsafeObj")

          fileRecord.setEscapeColumnDelimitersCSV(true)
          logger.debug(s"Set escape column delimiters for $unsafeObj")

          copyOptions.setBatchSize(512)
          copyOptions.setUseInternalTransaction(false)
          copyOptions.setTableLock(true)
          copyOptions.setKeepNulls(true)
          copyOptions.setBulkCopyTimeout(0) // no time limit; the bulk copy will wait indefinitely
          bulkCopy.setBulkCopyOptions(copyOptions)
          logger.debug(s"Set copy options for $unsafeObj")

          bulkCopy.setDestinationTableName(unsafeObj)
          logger.debug(s"Set destination table name to $unsafeObj")

          bulkCopy.writeToServer(fileRecord)
          logger.debug(s"Wrote bulk CSV to $unsafeObj")
        }
      }

      def release: Utilities => F[Unit] = {
        case (bulkCopy, fileRecord, _) => ConcurrentEffect[F] delay {
          bulkCopy.close()
          fileRecord.close()
          logger.debug(s"Closed bulk copy for $unsafeObj")
        }
      }

      ConcurrentEffect[F].bracket(acquire)(use)(release)
    }

    def prepareTable: ConnectionIO[Unit] =
      for {
        _ <- writeMode match {
          case WriteMode.Create =>
            createTable(logHandler)(objFragment, cols)

          case WriteMode.Replace =>
            replaceTable(logHandler)(objFragment, cols)

          case WriteMode.Truncate =>
            truncateTable(logHandler)(objFragment, unsafeTableName, unsafeTableSchema, cols)

          case WriteMode.Append =>
            appendToTable(logHandler)(objFragment, unsafeTableName, unsafeTableSchema, cols)
        }
      } yield ()

    bytes => {
      Stream.resource(xa.connect(xa.kernel)) flatMap { connection =>
        val unwrapped = connection.unwrap(classOf[SQLServerConnection])

        Stream.eval(prepareTable.transact(xa)) ++
          bytes.through(fs2.io.toInputStream[F]).evalMap(loadCsv(_, unwrapped))
      }
    }
  }

  ////

  private def toJdbcType(sqlServerType: SQLServerType): (JdbcType, Int, Int) =
    sqlServerType match {
      case SQLServerType.BIGINT => (JdbcType.BigInt, 0, 0)
      case SQLServerType.BIT => (JdbcType.Bit, 0, 0)
      case SQLServerType.CHAR(p) => (JdbcType.Char, p, 0)
      case SQLServerType.DATE => (JdbcType.Date, 0, 0)
      case SQLServerType.DATETIME => (JdbcType.Timestamp, 0, 0)
      case SQLServerType.DATETIME2(p) => (JdbcType.Timestamp, p, 0)
      case SQLServerType.DATETIMEOFFSET(p) => (JdbcType.MsSqlDateTimeOffset, p, 0)
      case SQLServerType.DECIMAL(p, s) => (JdbcType.Decimal, p, s)
      case SQLServerType.FLOAT(p) => (JdbcType.Double, p, 0)
      case SQLServerType.INT => (JdbcType.Integer, 0, 0)
      case SQLServerType.NCHAR(p) => (JdbcType.Char, p, 0)
      case SQLServerType.NUMERIC(p, s) => (JdbcType.Numeric, p, s)
      case SQLServerType.NTEXT => (JdbcType.LongVarChar, 0, 0)
      case SQLServerType.NVARCHAR(p) => (JdbcType.VarChar, p, 0)
      case SQLServerType.REAL => (JdbcType.Real, 0, 0)
      case SQLServerType.SMALLDATETIME => (JdbcType.Timestamp, 0, 0)
      case SQLServerType.SMALLINT => (JdbcType.SmallInt, 0, 0)
      case SQLServerType.TEXT => (JdbcType.LongVarChar, 0, 0)
      case SQLServerType.TIME(p) => (JdbcType.Time, p, 0)
      case SQLServerType.TINYINT => (JdbcType.TinyInt, 0, 0)
      case SQLServerType.VARCHAR(p) => (JdbcType.VarChar, p, 0)
    }
}
