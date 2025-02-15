@file:Suppress("EXPERIMENTAL_API_USAGE_FUTURE_ERROR")

package no.nav.arbeidsgiver.notifikasjon.executable.perftest

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.ContentType.Application.FormUrlEncoded
import kotlinx.coroutines.*
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.produsenter.FAGER_TESTPRODUSENT
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.produsenter.ServicecodeDefinisjon
import java.lang.System.currentTimeMillis
import java.time.Instant
import kotlin.random.Random
import kotlin.system.measureTimeMillis

fun main() = runBlocking {
    client.use {
        nyBeskjed(1, Api.PRODUSENT_LOCAL)
        nySak(3, Api.PRODUSENT_LOCAL)

        //hentNotifikasjoner(1, Api.BRUKER_GCP)
    }
}

val client = HttpClient(Apache) {
    engine {
        socketTimeout = 0
        connectTimeout = 0
        connectionRequestTimeout = 0
        customizeClient {
            setMaxConnTotal(250)
        }
    }
}
const val selvbetjeningToken =
    "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6ImZ5akpfczQwN1ZqdnRzT0NZcEItRy1IUTZpYzJUeDNmXy1JT3ZqVEFqLXcifQ.eyJleHAiOjE2MjQ0NTIwMTgsIm5iZiI6MTYyNDQ0ODQxOCwidmVyIjoiMS4wIiwiaXNzIjoiaHR0cHM6Ly9uYXZ0ZXN0YjJjLmIyY2xvZ2luLmNvbS9kMzhmMjVhYS1lYWI4LTRjNTAtOWYyOC1lYmY5MmMxMjU2ZjIvdjIuMC8iLCJzdWIiOiIxNjEyMDEwMTE4MSIsImF1ZCI6IjAwOTBiNmUxLWZmY2MtNGMzNy1iYzIxLTA0OWY3ZDFmMGZlNSIsImFjciI6IkxldmVsNCIsIm5vbmNlIjoiTDlCaVlsMlNYVGVVNm1zckpuZUFwN05DUk9qcFZKQ09QRVNZWk9fQ2IwcyIsImlhdCI6MTYyNDQ0ODQxOCwiYXV0aF90aW1lIjoxNjI0NDQ4NDE3LCJqdGkiOiJSQ3praU1fQmhaajZtRVFvTWk1YjBMV1d6Z3hlSTFWVzkzRExaZGctOUpnIiwiYXRfaGFzaCI6IlRVN24xNnBVVElRR0ZVdUgzdUE5a1EifQ.cVxMow7vGKi_w4jsxtr3XVDfz9i1uQw7fH6KdRSU4R8ahtONaGiiBstXw6Ega1PLJ2NPN1bb5p2Bi9BsdTiXxbtjLIquS1pHC3UgQOeEBx3YOdgfjiPOXWjXYqGciIBWbzjjKRvGkYysICPudrL0YDVMmsPIY90-KclQRbaoSPMnRosmwoF_VEK5PJ8EWpePTzDMwD-W2oR2yQ-0SaX8d4K8Rqzwrz-1aKU4hPVgBYxd4eLapdDS39xEU-l1v3j8zCXp67Vvmq6XJdat6H9IJ9_Ok7fU9E62zjbHliMETe3If53aS8-YXJevI0S1qxH4wROJud0d1595U5yqDFEhjA"

val tokenDingsToken: String = runBlocking {
    client.post("https://fakedings.dev-gcp.nais.io/fake/custom") {
        contentType(FormUrlEncoded)
        setBody(
            "azp=dev-gcp:fager:notifikasjon-test-produsent\n" +
                    "\n&aud=produsent-api"
        )
    }
        .bodyAsText()
}

suspend fun concurrentWithStats(
    title: String = "work",
    times: Int,
    work: suspend () -> Unit
) = coroutineScope {
    val progressBar = ProgressBar(title = title, times)
    val start = currentTimeMillis()
    val stats = (1..times).map {
        async {
            val jitter = Random.nextLong(0, 2000)
            delay(jitter)
            kotlin.runCatching {
                val ms = measureTimeMillis {
                    work.invoke()
                }
                ms
            }.onSuccess {
                progressBar.add()
            }.onFailure {
                println()
                println(it)
                progressBar.add()
            }
        }
    }.awaitAll()
    val durationMs = currentTimeMillis() - start
    val statsSuccess = stats.filter { it.isSuccess }.map { it.getOrThrow() }
    val statsFailure = stats.filter { it.isFailure }
    println(
        """
        |----------------------------
        | $title stats:
        |  Error: ${((statsFailure.count().toDouble() / stats.count()) * 100).toInt()}%
        |  
        |     Duration: ${durationMs}ms 
        |     Ok count: ${statsSuccess.count()}
        |  Error count: ${statsFailure.count()}
        |  Total Count: ${stats.count()}
        |  
        |  Ok stats:
        |       Max: ${statsSuccess.maxOrNull()}ms
        |       Min: ${statsSuccess.minOrNull()}ms
        |   Average: ${statsSuccess.average().toInt()}ms
        |       Sum: ${statsSuccess.sum()}ms
        |     req/s: ${statsSuccess.count() / (durationMs / 1000)}
        |----------------------------
        |""".trimMargin()
    )
}

enum class Api(val url: String) {
    BRUKER_LOCAL("http://localhost:8082/api/graphql"),
    PRODUSENT_LOCAL("http://localhost:8081/api/graphql"),
    BRUKER_GCP("https://ag-notifikasjon-bruker-api.dev.nav.no/api/graphql"),
    PRODUSENT_GCP("https://ag-notifikasjon-produsent-api.dev.nav.no/api/graphql"),
}


suspend fun hentNotifikasjoner(count: Int, api: Api = Api.BRUKER_GCP) {
    concurrentWithStats("hentNotifikasjoner", count) {
        client.post(api.url) {
            headers {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.Authorization, "Bearer $selvbetjeningToken")
            }
            val query = """
                    {
                         notifikasjoner {
                             ...on Beskjed {
                                 lenke
                                 tekst
                                 merkelapp
                                 opprettetTidspunkt
                             }
                         }
                     }
            """.trimIndent()
            setBody(
                """{
                     "query": "$query"
                    }""".trimMarginAndNewline()
            )
        }
            .body<HttpResponse>()
    }
}

suspend fun nyBeskjed(count: Int, api: Api = Api.PRODUSENT_GCP) {
    val run = Instant.now()
    val eksterIder = generateSequence(1) { it + 1 }.iterator()
    val tjenester = FAGER_TESTPRODUSENT.tillatteMottakere
        .filterIsInstance<ServicecodeDefinisjon>()
        .asSequence()
        .looping()
        .iterator()
    val virksomhetsnummere = ("0123456789".map { it.toString().repeat(9) } + listOf("910825631"))
        .asSequence()
        .looping()
        .iterator()
    concurrentWithStats("nyBeskjed", count) {
        val (tjenesteKode, tjenesteVersjon) = tjenester.next()
        val virksomhet = virksomhetsnummere.next()
        client.post(api.url) {
            headers {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.Authorization, "Bearer $tokenDingsToken")
            }
            setBody(
                """{
                     "query": "mutation {
                          nyBeskjed(nyBeskjed: {
                              notifikasjon: {
                                lenke: \"https://min-side-arbeidsgiver.dev.nav.no/min-side-arbeidsgiver/?bedrift=$virksomhet\",
                                tekst: \"Dette er en test.\",
                                merkelapp: \"fager\",
                              }
                              mottaker: {
                                  altinn: {
                                      serviceCode: \"$tjenesteKode\",
                                      serviceEdition: \"$tjenesteVersjon\",
                                  }
                              },
                              metadata: {
                                eksternId: \"$run-${eksterIder.next()}\"
                                virksomhetsnummer: \"$virksomhet\"

                              }
                          }) {
                              __typename
                              ... on NyBeskjedVellykket {
                                id
                              }
                              ... on Error {
                                feilmelding
                              }
                          }
                     }"
                    }""".trimMarginAndNewline()
            )
        }
            .body<HttpResponse>()
    }
}

suspend fun nySak(count: Int, api: Api = Api.PRODUSENT_GCP) {
    val navn = listOf("Tor", "Per", "Ragnhild", "Muhammed", "Sara", "Alex", "Nina")
    val typeSak = listOf("er syk", "har lønnstilskudd", "har mentortilskudd")
    fun genererTittel() = "${navn.random()} ${typeSak.random()}."
    val tjenester = FAGER_TESTPRODUSENT.tillatteMottakere.take(1)
        .filterIsInstance<ServicecodeDefinisjon>()
        .asSequence()
        .looping()
        .iterator()
    val virksomhetsnummere = listOf("910825585")//+ ("0123456789".map { it.toString().repeat(9) })
        .asSequence()
        .looping()
        .iterator()
    concurrentWithStats("nySak", count) {
        val (tjenesteKode, tjenesteVersjon) = tjenester.next()
        val virksomhet = virksomhetsnummere.next()
        client.post(api.url) {
            headers {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.Authorization, "Bearer $tokenDingsToken")
            }
            setBody(
                """{
                     "query": "mutation {
                          nySak(
                              grupperingsid: \"${java.util.UUID.randomUUID()}\"
                              merkelapp: \"fager\"
                              virksomhetsnummer: \"$virksomhet\"
                              mottakere: [
                                  {altinn: {
                                      serviceCode: \"$tjenesteKode\",
                                      serviceEdition: \"$tjenesteVersjon\"
                                  }}
                              ]
                              
                              tittel: \"${genererTittel()}\",                              
                              lenke: \"https://min-side-arbeidsgiver.dev.nav.no/min-side-arbeidsgiver/?bedrift=$virksomhet\",
                              initiellStatus: MOTTATT
                          ) {
                              __typename
                              ... on NySakVellykket {
                                id
                              }
                              ... on Error {
                                feilmelding
                              }
                          }
                     }"
                    }""".trimMarginAndNewline()
            )
        }
            .body<HttpResponse>()
    }
}

class ProgressBar(
    val title: String,
    private val count: Int,
    private val size: Int = 100
) {
    private var curr = 0

    fun add(i: Int = 1) {
        curr += i
        print()
    }

    private fun print() {
        print(progressbar)
        if (percent == 100) {
            println("")
            println("$title complete!")
        }
    }

    private val percent get() = ((curr.toDouble() / count) * 100).toInt()
    private val progress get() = (size.toDouble() * (percent.toDouble() / 100)).toInt()
    private val progressbar: String
        get() {
            val hashes = (0..size).joinToString("") {
                when {
                    it <= progress -> "#"
                    else -> " "
                }
            }
            return "\r$title [$hashes] $percent% ($curr / $count)"
        }
}

fun String.trimMarginAndNewline() =
    this.trimMargin().replace(Regex("\n"), " ")

fun <T> Sequence<T>.looping() = generateSequence(this) { it }.flatten()