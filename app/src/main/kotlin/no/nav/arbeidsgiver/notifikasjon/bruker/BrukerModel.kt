package no.nav.arbeidsgiver.notifikasjon.bruker

import no.nav.arbeidsgiver.notifikasjon.*
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.Database
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.Health
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.Transaction
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.coRecord
import no.nav.arbeidsgiver.notifikasjon.produsent.ProdusentModel
import java.time.OffsetDateTime
import java.util.*

interface BrukerModel {
    sealed interface Tilgang {
        data class Altinn(
            val virksomhet: String,
            val servicecode: String,
            val serviceedition: String,
        ) : Tilgang

        data class AltinnReportee(
            val virksomhet: String,
            val fnr: String,
        ) : Tilgang

        data class AltinnRolle(
            val virksomhet: String,
            val roleDefinitionId: String,
            val roleDefinitionCode: String,
        ) : Tilgang
    }

    sealed interface Notifikasjon {
        val id: UUID
        val virksomhetsnummer: String
    }

    data class Beskjed(
        val merkelapp: String,
        val tekst: String,
        val grupperingsid: String? = null,
        val lenke: String,
        val eksternId: String,
        override val virksomhetsnummer: String,
        val opprettetTidspunkt: OffsetDateTime,
        override val id: UUID,
        val klikketPaa: Boolean
    ) : Notifikasjon

    data class Oppgave(
        val merkelapp: String,
        val tekst: String,
        val grupperingsid: String? = null,
        val lenke: String,
        val eksternId: String,
        override val virksomhetsnummer: String,
        val opprettetTidspunkt: OffsetDateTime,
        override val id: UUID,
        val klikketPaa: Boolean,
        val tilstand: Tilstand,
    ) : Notifikasjon {
        @Suppress("unused")
        /* leses fra database */
        enum class Tilstand {
            NY,
            UTFOERT
        }
    }

    suspend fun hentNotifikasjoner(
        fnr: String,
        tilganger: Collection<Tilgang>,
    ): List<Notifikasjon>

    suspend fun oppdaterModellEtterHendelse(hendelse: Hendelse)
    suspend fun virksomhetsnummerForNotifikasjon(notifikasjonsid: UUID): String?
}

class BrukerModelImpl(
    private val database: Database
) : BrukerModel {
    private val timer = Health.meterRegistry.timer("query_model_repository_hent_notifikasjoner")

    override suspend fun hentNotifikasjoner(
        fnr: String,
        tilganger: Collection<BrukerModel.Tilgang>,
    ): List<BrukerModel.Notifikasjon> = timer.coRecord {
        val tilgangerAltinnMottaker = tilganger.filterIsInstance<BrukerModel.Tilgang.Altinn>().map {
            AltinnMottaker(
                serviceCode = it.servicecode,
                serviceEdition = it.serviceedition,
                virksomhetsnummer = it.virksomhet
            )
        }
        val tilgangerAltinnReporteeMottaker = tilganger.filterIsInstance<BrukerModel.Tilgang.AltinnReportee>().map {
            AltinnReporteeMottaker(
                virksomhetsnummer = it.virksomhet,
                fnr = it.fnr
            )
        }
        val tilgangerAltinnRolleMottaker = tilganger.filterIsInstance<BrukerModel.Tilgang.AltinnRolle>().map {
            AltinnRolleMottaker(
                virksomhetsnummer = it.virksomhet,
                roleDefinitionId = it.roleDefinitionId,
                roleDefinitionCode = it.roleDefinitionCode,
            )
        }

        database.nonTransactionalExecuteQuery(
            /*  quotes are necessary for fields from json, otherwise they are lower-cased */
            """
            with 
                mine_altinntilganger as (
                    select * from json_to_recordset(?::json) 
                    as (virksomhetsnummer text, "serviceCode" text, "serviceEdition" text)
                ),
                mine_altinnreporteetilganger as (
                    select * from json_to_recordset(?::json) 
                    as (virksomhetsnummer text, "fnr" text)
                ),
                mine_altinnrolletilganger as (
                    select * from json_to_recordset(?::json) 
                    as (virksomhetsnummer text, "roleDefinitionId" text, "roleDefinitionCode" text)
                ),
                mine_altinn_notifikasjoner as (
                    select er.notifikasjon_id
                    from mottaker_altinn_enkeltrettighet er
                    join mine_altinntilganger at on 
                        er.virksomhet = at.virksomhetsnummer and
                        er.service_code = at."serviceCode" and
                        er.service_edition = at."serviceEdition"
                ),
                mine_altinn_reportee_notifikasjoner as (
                    select rep.notifikasjon_id
                    from mottaker_altinn_reportee rep
                    join mine_altinnreporteetilganger at on 
                        rep.virksomhet = at.virksomhetsnummer and
                        rep.fnr = at."fnr"
                ),
                mine_altinn_rolle_notifikasjoner as (
                    select rol.notifikasjon_id
                    from mottaker_altinn_rolle rol
                    join mine_altinnrolletilganger at on 
                        rol.virksomhet = at.virksomhetsnummer and
                        rol.role_definition_id = at."roleDefinitionId" and
                        rol.role_definition_code = at."roleDefinitionCode"
                ),
                mine_digisyfo_notifikasjoner as (
                    select notifikasjon_id 
                    from notifikasjoner_for_digisyfo_fnr
                    where fnr_leder = ?
                ),
                mine_notifikasjoner as (
                    (select * from mine_digisyfo_notifikasjoner)
                    union 
                    (select * from mine_altinn_notifikasjoner)
                    union 
                    (select * from mine_altinn_reportee_notifikasjoner)
                    union 
                    (select * from mine_altinn_rolle_notifikasjoner)
                )
            select 
                n.*, 
                klikk.notifikasjonsid is not null as klikketPaa
            from mine_notifikasjoner as mn
            join notifikasjon as n on n.id = mn.notifikasjon_id
            left outer join brukerklikk as klikk on
                klikk.notifikasjonsid = n.id
                and klikk.fnr = ?
            order by opprettet_tidspunkt desc
            limit 200
            """,
            {
                jsonb(tilgangerAltinnMottaker)
                jsonb(tilgangerAltinnReporteeMottaker)
                jsonb(tilgangerAltinnRolleMottaker)
                string(fnr)
                string(fnr)
            }
        ) {
            when (val type = getString("type")) {
                "BESKJED" -> BrukerModel.Beskjed(
                    merkelapp = getString("merkelapp"),
                    tekst = getString("tekst"),
                    grupperingsid = getString("grupperingsid"),
                    lenke = getString("lenke"),
                    eksternId = getString("ekstern_id"),
                    virksomhetsnummer = getString("virksomhetsnummer"),
                    opprettetTidspunkt = getObject("opprettet_tidspunkt", OffsetDateTime::class.java),
                    id = getObject("id", UUID::class.java),
                    klikketPaa = getBoolean("klikketPaa")
                )
                "OPPGAVE" -> BrukerModel.Oppgave(
                    merkelapp = getString("merkelapp"),
                    tilstand = BrukerModel.Oppgave.Tilstand.valueOf(getString("tilstand")),
                    tekst = getString("tekst"),
                    grupperingsid = getString("grupperingsid"),
                    lenke = getString("lenke"),
                    eksternId = getString("ekstern_id"),
                    virksomhetsnummer = getString("virksomhetsnummer"),
                    opprettetTidspunkt = getObject("opprettet_tidspunkt", OffsetDateTime::class.java),
                    id = getObject("id", UUID::class.java),
                    klikketPaa = getBoolean("klikketPaa")
                )
                else ->
                    throw Exception("Ukjent notifikasjonstype '$type'")
            }
        }
    }

    override suspend fun virksomhetsnummerForNotifikasjon(notifikasjonsid: UUID): String? =
        database.nonTransactionalExecuteQuery(
            """
                SELECT virksomhetsnummer FROM notifikasjon WHERE id = ? LIMIT 1
            """, {
                uuid(notifikasjonsid)
            }) {
            getString("virksomhetsnummer")!!
        }.getOrNull(0)

    override suspend fun oppdaterModellEtterHendelse(hendelse: Hendelse) {
        /* when-expressions gives error when not exhaustive, as opposed to when-statement. */
        val ignored: Unit = when (hendelse) {
            is Hendelse.SakOpprettet -> TODO()
            is Hendelse.NyStatusSak -> TODO()
            is Hendelse.BeskjedOpprettet -> oppdaterModellEtterBeskjedOpprettet(hendelse)
            is Hendelse.BrukerKlikket -> oppdaterModellEtterBrukerKlikket(hendelse)
            is Hendelse.OppgaveOpprettet -> oppdaterModellEtterOppgaveOpprettet(hendelse)
            is Hendelse.OppgaveUtført -> oppdaterModellEtterOppgaveUtført(hendelse)
            is Hendelse.SoftDelete -> oppdaterModellEtterDelete(hendelse.aggregateId)
            is Hendelse.HardDelete -> oppdaterModellEtterDelete(hendelse.aggregateId)
            is Hendelse.EksterntVarselFeilet -> Unit
            is Hendelse.EksterntVarselVellykket -> Unit
        }
    }

    private suspend fun oppdaterModellEtterDelete(aggregateId: UUID) {
        database.transaction({
            throw RuntimeException("Delete", it)
        }) {
            executeUpdate(""" DELETE FROM notifikasjon WHERE id = ?;""") {
                uuid(aggregateId)
            }

            executeUpdate("""DELETE FROM brukerklikk WHERE notifikasjonsid = ?;""") {
                uuid(aggregateId)
            }
        }
    }

    private suspend fun oppdaterModellEtterOppgaveUtført(utførtHendelse: Hendelse.OppgaveUtført) {
        database.nonTransactionalExecuteUpdate(
            """
            UPDATE notifikasjon
            SET tilstand = '${ProdusentModel.Oppgave.Tilstand.UTFOERT}'
            WHERE id = ?
        """
        ) {
            uuid(utførtHendelse.notifikasjonId)
        }
    }

    private suspend fun oppdaterModellEtterBrukerKlikket(brukerKlikket: Hendelse.BrukerKlikket) {
        database.nonTransactionalExecuteUpdate(
            """
            INSERT INTO brukerklikk(fnr, notifikasjonsid) VALUES (?, ?)
            ON CONFLICT ON CONSTRAINT brukerklikk_pkey
            DO NOTHING
        """
        ) {
            string(brukerKlikket.fnr)
            uuid(brukerKlikket.notifikasjonId)
        }
    }

    private suspend fun oppdaterModellEtterBeskjedOpprettet(beskjedOpprettet: Hendelse.BeskjedOpprettet) {
        database.transaction {
            executeUpdate(
                """
                insert into notifikasjon(
                    type,
                    tilstand,
                    id,
                    merkelapp,
                    tekst,
                    grupperingsid,
                    lenke,
                    ekstern_id,
                    opprettet_tidspunkt,
                    virksomhetsnummer
                )
                values ('BESKJED', 'NY', ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict on constraint notifikasjon_pkey do nothing;
            """
            ) {
                uuid(beskjedOpprettet.notifikasjonId)
                string(beskjedOpprettet.merkelapp)
                string(beskjedOpprettet.tekst)
                nullableString(beskjedOpprettet.grupperingsid)
                string(beskjedOpprettet.lenke)
                string(beskjedOpprettet.eksternId)
                timestamptz(beskjedOpprettet.opprettetTidspunkt)
                string(beskjedOpprettet.virksomhetsnummer)
            }

            for (mottaker in beskjedOpprettet.mottakere) {
                storeMottaker(beskjedOpprettet.notifikasjonId, mottaker)
            }
        }
    }

    private fun Transaction.storeMottaker(notifikasjonId: UUID, mottaker: Mottaker) {
        val ignored = when (mottaker) {
            is NærmesteLederMottaker -> storeNærmesteLederMottaker(notifikasjonId, mottaker)
            is AltinnMottaker -> storeAltinnMottaker(notifikasjonId, mottaker)
            is AltinnReporteeMottaker -> storeAltinnReporteeMottaker(notifikasjonId, mottaker)
            is AltinnRolleMottaker -> storeAltinnRolleMottaker(notifikasjonId, mottaker)
        }
    }

    private fun Transaction.storeNærmesteLederMottaker(notifikasjonId: UUID, mottaker: NærmesteLederMottaker) {
        executeUpdate("""
            insert into mottaker_digisyfo(notifikasjon_id, virksomhet, fnr_leder, fnr_sykmeldt)
            values (?, ?, ?, ?)
        """) {
            uuid(notifikasjonId)
            string(mottaker.virksomhetsnummer)
            string(mottaker.naermesteLederFnr)
            string(mottaker.ansattFnr)
        }
    }

    private fun Transaction.storeAltinnMottaker(notifikasjonId: UUID, mottaker: AltinnMottaker) {
        executeUpdate("""
            insert into mottaker_altinn_enkeltrettighet
                (notifikasjon_id, virksomhet, service_code, service_edition)
            values (?, ?, ?, ?)
        """) {
            uuid(notifikasjonId)
            string(mottaker.virksomhetsnummer)
            string(mottaker.serviceCode)
            string(mottaker.serviceEdition)
        }
    }

    private fun Transaction.storeAltinnReporteeMottaker(notifikasjonId: UUID, mottaker: AltinnReporteeMottaker) {
        executeUpdate("""
            insert into mottaker_altinn_reportee
                (notifikasjon_id, virksomhet, fnr)
            values (?, ?, ?)
        """) {
            uuid(notifikasjonId)
            string(mottaker.virksomhetsnummer)
            string(mottaker.fnr)
        }
    }

    private fun Transaction.storeAltinnRolleMottaker(notifikasjonId: UUID, mottaker: AltinnRolleMottaker) {
        executeUpdate(
            """
            insert into mottaker_altinn_rolle
                (notifikasjon_id, virksomhet, role_definition_code, role_definition_id)
            values (?, ?, ?, ?)
        """
        ) {
            uuid(notifikasjonId)
            string(mottaker.virksomhetsnummer)
            string(mottaker.roleDefinitionCode)
            string(mottaker.roleDefinitionId)
        }
    }


    private suspend fun oppdaterModellEtterOppgaveOpprettet(oppgaveOpprettet: Hendelse.OppgaveOpprettet) {
        database.transaction {
            executeUpdate(
                """
                insert into notifikasjon(
                    type,
                    tilstand,
                    id,
                    merkelapp,
                    tekst,
                    grupperingsid,
                    lenke,
                    ekstern_id,
                    opprettet_tidspunkt,
                    virksomhetsnummer
                )
                values ('OPPGAVE', 'NY', ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict on constraint notifikasjon_pkey do nothing;
            """
            ) {
                uuid(oppgaveOpprettet.notifikasjonId)
                string(oppgaveOpprettet.merkelapp)
                string(oppgaveOpprettet.tekst)
                nullableString(oppgaveOpprettet.grupperingsid)
                string(oppgaveOpprettet.lenke)
                string(oppgaveOpprettet.eksternId)
                timestamptz(oppgaveOpprettet.opprettetTidspunkt)
                string(oppgaveOpprettet.virksomhetsnummer)
            }

            for (mottaker in oppgaveOpprettet.mottakere) {
                storeMottaker(oppgaveOpprettet.notifikasjonId, mottaker)
            }
        }
    }
 }
