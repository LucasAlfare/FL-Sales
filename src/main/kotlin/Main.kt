import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

const val ONE_CENT = 1
const val ONE_REAL = 100 * ONE_CENT

private val dateRegex = Regex(pattern = """\d{2}-\d{2}-\d{4}""")

enum class PaymentMethod {
  Cash, Pix, Debit
}

@Serializable
data class SaleCreateDTO(
  val date: String,
  val paymentMethod: PaymentMethod = PaymentMethod.Cash,
  val quantity: Int = 1,
  val relatedProductName: String
) {
  init {
    require(date.matches(dateRegex)) {
      "Date in wrong format: [$date]"
    }

    // TODO: validate if the received date is in a possible date range

    require(quantity >= 1) { "Invalid quantity: [$quantity]." }

    require(relatedProductName.isNotEmpty()) { "Empty related product name." }
  }
}

@Serializable
data class SalesReportResponseDTO(
  val date: String,
  var totalCash: Int = 0,
  var totalPix: Int = 0,
  var totalDebit: Int = 0,
  var total: Int = 0,
  var totalCost: Int = 0,
  var profit: Int = 0,
  val frequencies: MutableMap<String, Int> = mutableMapOf()
) {

  private fun defineTotal() {
    total = totalCash + totalPix + totalDebit
  }

  fun defineProfit(defineTotal: Boolean = true) {
    if (defineTotal) defineTotal()
    profit = total - totalCost
  }
}

object Products : IntIdTable("Products") {
  val name = text("name").uniqueIndex()
  val price = integer("price").default(0) // in cents
  val productionCost = integer("production_cost").default(0) // in cents
}

object Sales : IntIdTable("Sales") {
  val date = varchar(name = "date", length = "dd-MM-yyyy".length)
  val paymentMethod = enumeration<PaymentMethod>("payment_method")
  val quantity = integer("quantity")
  val relatedProductName = text("related_product_name").references(Products.name)
}

suspend fun main() {
  initDatabase()

  runCatching {
    AppDB.query {
      Products.insert {
        it[name] = "product 1"
        it[price] = 20 * ONE_REAL
        it[productionCost] = 15 * ONE_REAL
      }

      Products.insert {
        it[name] = "product 2"
        it[price] = 30 * ONE_REAL
        it[productionCost] = 10 * ONE_REAL
      }
    }
  }

  embeddedServer(Netty, port = 80) {
    configureCORS()
    configureSerialization()
    configureRouting()
  }.start(true)
}

fun initDatabase(dropTablesOnStart: Boolean = false) {
  AppDB.initialize(
    jdbcUrl = System.getenv("DB_JDBC_URL") ?: Constants.SQLITE_URL,
    jdbcDriverClassName = System.getenv("DB_JDBC_DRIVER") ?: Constants.SQLITE_DRIVER,
    username = System.getenv("DB_USERNAME") ?: "",
    password = System.getenv("DB_PASSWORD") ?: ""
  ) {
    if (dropTablesOnStart) {
      SchemaUtils.drop(
        Products,
        Sales
      )
    }

    transaction(AppDB.DB) {
      SchemaUtils.createMissingTablesAndColumns(
        Products,
        Sales
      )
    }
  }
}

fun Application.configureCORS() {
  install(CORS) {
    anyHost()
    allowHeader(HttpHeaders.ContentType)
  }
}

fun Application.configureSerialization() {
  install(ContentNegotiation) {
    json(Json { isLenient = false; prettyPrint = true })
  }
}

fun Application.configureRouting() {
  routing {
    staticFiles("/create_sale", File("files")) {
      default("create_sale.html")
      preCompressed(CompressedFileType.GZIP)
    }

    staticFiles("/get_report", File("files")) {
      default("get_report.html")
      preCompressed(CompressedFileType.GZIP)
    }

    get("/reports/{date}") {
      val date = try {
        call.parameters["date"].let {
          if (it == null || !it.matches(dateRegex)) {
            return@get call.respond(HttpStatusCode.BadRequest, "bad URL.")
          } else {
            it
          }
        }
      } catch (e: Exception) {
        return@get call.respond(HttpStatusCode.BadRequest, "bad URL.")
      }

      val report = SalesReportResponseDTO(date = date)

      try {
        AppDB.query {
          (Sales leftJoin Products)
            .selectAll()
            .where { Sales.date eq date }
            .map {
              val currName = it[Sales.relatedProductName]
              val currQuantity = it[Sales.quantity]
              val currMethod = it[Sales.paymentMethod]
              val currPrice = it[Products.price] * currQuantity
              val currCost = it[Products.productionCost] * currQuantity

              when (currMethod) {
                PaymentMethod.Cash -> report.totalCash += currPrice
                PaymentMethod.Pix -> report.totalPix += currPrice
                PaymentMethod.Debit -> report.totalDebit += currPrice
              }

              report.totalCost += currCost

              if (!report.frequencies.containsKey(currName)) {
                report.frequencies[currName] = 1
              } else {
                val f = report.frequencies[currName]!!
                report.frequencies[currName] = f + 1
              }
            }
        }

        report.defineProfit()

        return@get call.respond(HttpStatusCode.OK, report)
      } catch (e: Exception) {
        return@get call.respond(HttpStatusCode.InternalServerError, "error creating the report.")
      }
    }

    post("/sales") {
      val sale = try {
        call.receive<SaleCreateDTO>()
      } catch (e: Exception) {
        return@post call.respond(HttpStatusCode.BadRequest, "serialization error.")
      }

      try {
        AppDB.query {
          Sales.insertAndGetId {
            it[date] = sale.date
            it[paymentMethod] = sale.paymentMethod
            it[quantity] = sale.quantity
            it[relatedProductName] = sale.relatedProductName
          }
        }.let {
          return@post call.respond(HttpStatusCode.OK, it.value)
        }
      } catch (e: Exception) {
        return@post call.respond(HttpStatusCode.InternalServerError, "error inserting sale in the database")
      }
    }
  }
}