package no.nav.arbeidsgiver.notifikasjon.bruker

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.beBlank
import io.kotest.matchers.types.beOfType
import io.ktor.http.*
import io.mockk.MockKAssertScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel.BrukerKlikket
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseProdusent
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.graphql.GraphQLRequest
import no.nav.arbeidsgiver.notifikasjon.util.BRUKER_HOST
import no.nav.arbeidsgiver.notifikasjon.util.SELVBETJENING_TOKEN
import no.nav.arbeidsgiver.notifikasjon.util.getGraphqlErrors
import no.nav.arbeidsgiver.notifikasjon.util.getTypedContent
import no.nav.arbeidsgiver.notifikasjon.util.ktorBrukerTestServer
import no.nav.arbeidsgiver.notifikasjon.util.post
import java.util.*

class KlikkPåNotifikasjonGraphQLTests : DescribeSpec({
    val queryModel = mockk<BrukerRepositoryImpl>(relaxed = true)
    val kafkaProducer = mockk<HendelseProdusent>()

    val engine = ktorBrukerTestServer(
        brukerRepository = queryModel,
        kafkaProducer = kafkaProducer,
    )

    coEvery { kafkaProducer.send(any<BrukerKlikket>()) } returns Unit

    afterSpec {
        unmockkAll()
    }

    describe("bruker-api: rapporterer om at notifikasjon er klikket på") {
        context("uklikket-notifikasjon eksisterer for bruker") {
            val id = UUID.fromString("09d5a598-b31a-11eb-8529-0242ac130003")
            coEvery { queryModel.virksomhetsnummerForNotifikasjon(id) } returns "1234"

            val httpResponse = engine.post(
                "/api/graphql",
                host = BRUKER_HOST,
                jsonBody = GraphQLRequest(
                    """
                        mutation {
                            notifikasjonKlikketPaa(id: "$id") {
                                __typename
                                ... on BrukerKlikk {
                                    id
                                    klikketPaa
                                }
                            }
                        }
                    """.trimIndent()
                ),
                accept = "application/json",
                authorization = "Bearer $SELVBETJENING_TOKEN"
            )

            it("ingen http/graphql-feil") {
                httpResponse.status() shouldBe HttpStatusCode.OK
                httpResponse.getGraphqlErrors() should beEmpty()
            }

            val graphqlSvar =
                httpResponse.getTypedContent<BrukerAPI.NotifikasjonKlikketPaaResultat>("notifikasjonKlikketPaa")

            it("ingen domene-feil") {
                graphqlSvar should beOfType<BrukerAPI.BrukerKlikk>()
            }

            it("response inneholder klikketPaa og id") {
                val brukerKlikk = graphqlSvar as BrukerAPI.BrukerKlikk
                brukerKlikk.klikketPaa shouldBe true
                brukerKlikk.id shouldNot beBlank()
            }

            val brukerKlikketMatcher: MockKAssertScope.(BrukerKlikket) -> Unit = { brukerKlikket ->
                brukerKlikket.fnr shouldNot beBlank()
                brukerKlikket.notifikasjonId shouldBe id

                /* For øyeblikket feiler denne, siden virksomhetsnummer ikke hentes ut. */
                brukerKlikket.virksomhetsnummer shouldNot beBlank()
            }

            it("Event produseres på kafka") {
                coVerify {
                    kafkaProducer.send(withArg(brukerKlikketMatcher))
                }
            }

            it("Database oppdaters") {
                coVerify {
                    queryModel.oppdaterModellEtterHendelse(
                        withArg(brukerKlikketMatcher)
                    )
                }
            }
        }
    }
})