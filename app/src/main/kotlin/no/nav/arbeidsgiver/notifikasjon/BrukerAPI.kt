package no.nav.arbeidsgiver.notifikasjon

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.*
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.CompletableFuture

object BrukerAPI {
    private val log = logger()

    data class Context(
        val fnr: String,
        val token: String,
        override val coroutineScope: CoroutineScope
    ): WithCoroutineScope

    sealed class Notifikasjon {
        data class Beskjed(
            val merkelapp: String,
            val tekst: String,
            val lenke: String,
            val opprettetTidspunkt: OffsetDateTime,
            val id: UUID,
            val brukerKlikk: BrukerKlikk,
            val virksomhet: Virksomhet,
        ) : Notifikasjon()

        data class Oppgave(
            val merkelapp: String,
            val tekst: String,
            val lenke: String,
            val opprettetTidspunkt: OffsetDateTime,
            val id: UUID,
            val brukerKlikk: BrukerKlikk,
            val virksomhet: Virksomhet,
        ) : Notifikasjon()
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "__typename")
    sealed class NotifikasjonKlikketPaaResultat

    @JsonTypeName("BrukerKlikk")
    data class BrukerKlikk(
        val id: String,
        val klikketPaa: Boolean
    ) : NotifikasjonKlikketPaaResultat()

    @JsonTypeName("UgyldigId")
    data class UgyldigId(
        val feilmelding: String
    ) : NotifikasjonKlikketPaaResultat()

    data class Virksomhet(
        val virksomhetsnummer: String,
        val navn: String? = null
    )

    fun createBrukerGraphQL(
        altinn: Altinn,
        brreg: Brreg,
        queryModelFuture: CompletableFuture<QueryModel>,
        kafkaProducer: CoroutineProducer<KafkaKey, Hendelse>
    ) = TypedGraphQL<Context>(
        createGraphQL("/bruker.graphqls") {
            scalar(Scalars.ISO8601DateTime)

            resolveSubtypes<Notifikasjon>()
            resolveSubtypes<NotifikasjonKlikketPaaResultat>()

            wire("Query") {
                dataFetcher("ping") {
                    "pong"
                }

                coDataFetcher("notifikasjoner") { env ->
                    val context = env.getContext<Context>()
                    val tilganger = altinn.hentAlleTilganger(context.fnr, context.token)

                    return@coDataFetcher queryModelFuture.await()
                        .hentNotifikasjoner(context.fnr, tilganger)
                        .map { notifikasjon ->
                            when (notifikasjon) {
                                is QueryModel.Beskjed ->
                                    Notifikasjon.Beskjed(
                                        merkelapp = notifikasjon.merkelapp,
                                        tekst = notifikasjon.tekst,
                                        lenke = notifikasjon.lenke,
                                        opprettetTidspunkt = notifikasjon.opprettetTidspunkt,
                                        id = notifikasjon.id,
                                        virksomhet = Virksomhet(when (notifikasjon.mottaker) {
                                            is NærmesteLederMottaker -> notifikasjon.mottaker.virksomhetsnummer
                                            is AltinnMottaker -> notifikasjon.mottaker.virksomhetsnummer
                                        }),
                                        brukerKlikk = BrukerKlikk(
                                            id = "${context.fnr}-${notifikasjon.id}",
                                            klikketPaa = notifikasjon.klikketPaa
                                        )
                                    )
                                is QueryModel.Oppgave ->
                                    Notifikasjon.Oppgave(
                                        merkelapp = notifikasjon.merkelapp,
                                        tekst = notifikasjon.tekst,
                                        lenke = notifikasjon.lenke,
                                        opprettetTidspunkt = notifikasjon.opprettetTidspunkt,
                                        id = notifikasjon.id,
                                        virksomhet = Virksomhet(when (notifikasjon.mottaker) {
                                            is NærmesteLederMottaker -> notifikasjon.mottaker.virksomhetsnummer
                                            is AltinnMottaker -> notifikasjon.mottaker.virksomhetsnummer
                                        }),
                                        brukerKlikk = BrukerKlikk(
                                            id = "${context.fnr}-${notifikasjon.id}",
                                            klikketPaa = notifikasjon.klikketPaa
                                        )
                                    )
                            }
                        }
                }

                wire("Beskjed") {
                    coDataFetcher("virksomhet") { env ->
                        val source = env.getSource<Notifikasjon.Beskjed>()
                        if (env.selectionSet.contains("Virksomhet.navn")) {
                            brreg.hentEnhet(source.virksomhet.virksomhetsnummer).let { it ->
                                Virksomhet(
                                    virksomhetsnummer = it.organisasjonsnummer,
                                    navn = it.navn
                                )
                            }
                        } else {
                            source.virksomhet
                        }
                    }
                }

                dataFetcher("whoami") {
                    it.getContext<Context>().fnr
                }
            }

            wire("Mutation") {
                coDataFetcher("notifikasjonKlikketPaa") { env ->
                    val context = env.getContext<Context>()
                    val notifikasjonsid = env.getTypedArgument<UUID>("id")
                    val queryModel = queryModelFuture.await()

                    val virksomhetsnummer = queryModel.virksomhetsnummerForNotifikasjon(notifikasjonsid)
                        ?: return@coDataFetcher UgyldigId("")

                    val hendelse = Hendelse.BrukerKlikket(
                        notifikasjonsId = notifikasjonsid,
                        fnr = context.fnr,
                        virksomhetsnummer = virksomhetsnummer
                    )

                    kafkaProducer.brukerKlikket(hendelse)

                    queryModel.oppdaterModellEtterBrukerKlikket(hendelse)

                    BrukerKlikk(
                        id = "${context.fnr}-${hendelse.notifikasjonsId}",
                        klikketPaa = true
                    )
                }
            }
        }
    )
}