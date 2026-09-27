package com.tom.pharmascan

import java.util.Calendar
import java.util.Locale

/**
 * Parseur GS1 pour les DataMatrix de boîtes de médicaments.
 *
 * Un DataMatrix pharmaceutique français encode, sous forme d'Application
 * Identifiers (AI) concaténés :
 *   01 -> GTIN-14  (= "0" + CIP13 pour les produits français)
 *   17 -> date de péremption (AAMMJJ)
 *   10 -> numéro de lot (longueur variable)
 *   21 -> numéro de série (longueur variable, sérialisation FMD/Directive UE)
 *
 * Les AI à longueur variable sont terminées soit par le séparateur GS
 * (0x1D, FNC1), soit par la fin de la chaîne. Certains lecteurs remplacent
 * le GS par un autre caractère, d'où la normalisation en amont.
 */
object Gs1Parser {

    private const val GS = '\u001D'

    /**
     * Longueur des DONNÉES (hors AI) pour les AI à longueur fixe.
     * Table restreinte aux AI réalistement présentes sur un conditionnement.
     * Référence : GS1 General Specifications, section "Fixed-length AIs".
     */
    private val FIXED_LENGTH: Map<String, Int> = mapOf(
        "00" to 18, // SSCC
        "01" to 14, // GTIN
        "02" to 14, // GTIN des articles contenus
        "03" to 14,
        "04" to 16,
        "11" to 6,  // date de production
        "12" to 6,  // date d'échéance
        "13" to 6,  // date de conditionnement
        "14" to 6,
        "15" to 6,  // date de durabilité minimale (DLUO)
        "16" to 6,
        "17" to 6,  // date de péremption  <-- celle qui nous intéresse
        "18" to 6,
        "19" to 6,
        "20" to 2,  // variante produit
        "41" to 13
    )

    /** AI à 4 chiffres, longueur de données fixe de 6 (poids/mesures : 310n..369n). */
    private fun isFourDigitMeasureAi(ai2: String): Boolean =
        ai2.length == 2 && ai2[0] == '3' && ai2[1] in '1'..'6'

    data class Gs1Data(
        val raw: String,
        val fields: Map<String, String>,
        /** Problèmes bloquants : le scan doit être rejeté. */
        val errors: List<String>,
        /** Remarques non bloquantes : on enregistre quand même. */
        val notices: List<String>
    ) {
        val gtin: String? get() = fields["01"]
        val lot: String? get() = fields["10"]
        val serial: String? get() = fields["21"]
        val expiryRaw: String? get() = fields["17"]

        /**
         * CIP13 = les 13 derniers chiffres du GTIN-14 français (préfixé par "0").
         * Renvoie null si le GTIN ne ressemble pas à un code pharma français.
         */
        val cip13: String?
            get() {
                val g = gtin ?: return null
                return when {
                    g.length == 14 && g.startsWith("0") -> g.substring(1)
                    g.length == 13 -> g
                    else -> null
                }
            }

        /** Date de péremption au format ISO yyyy-MM-dd, ou null. */
        val expiryIso: String? get() = expiryRaw?.let { parseExpiry(it) }

        /**
         * True si le code est exploitable : un CIP13 extractible ET aucune
         * erreur bloquante (checksum, GTIN dégénéré, chaîne tronquée).
         */
        val isUsable: Boolean get() = cip13 != null && errors.isEmpty()

        /** Message le plus utile à afficher en cas de rejet. */
        val rejectionReason: String?
            get() = errors.firstOrNull()
                ?: if (cip13 == null) "aucun CIP13 extractible" else null
    }

    /**
     * Normalise la charge utile avant parsing :
     *  - retire le préfixe de symbologie ]d2 / ]d1 / ]C1 ajouté par certains lecteurs
     *  - remplace les représentations alternatives du séparateur GS par 0x1D
     *  - retire les espaces/retours parasites en début et fin
     */
    private fun normalize(input: String): String {
        var s = input.trim()
        for (prefix in listOf("]d2", "]d1", "]C1", "]e0", "]Q3")) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length)
                break
            }
        }
        // Certains lecteurs/claviers rendent le GS sous forme visible
        s = s.replace("<GS>", GS.toString())
            .replace("{GS}", GS.toString())
            .replace('\u241D', GS)   // symbole Unicode "␝"
            .replace('\u001E', GS)   // RS parfois émis à la place
        return s
    }

    fun parse(input: String): Gs1Data {
        val s = normalize(input)
        val fields = LinkedHashMap<String, String>()
        val errors = mutableListOf<String>()
        val notices = mutableListOf<String>()
        var i = 0

        while (i < s.length) {
            // On saute les séparateurs orphelins
            if (s[i] == GS) { i++; continue }

            if (i + 2 > s.length) {
                errors.add("Fin de chaîne inattendue à la position $i")
                break
            }
            val ai2 = s.substring(i, i + 2)
            if (!ai2.all { it.isDigit() }) {
                errors.add("AI non numérique « $ai2 » à la position $i, arrêt du parsing")
                break
            }

            if (isFourDigitMeasureAi(ai2)) {
                // AI sur 4 chiffres + 6 chiffres de données
                if (i + 4 + 6 > s.length) {
                    errors.add("AI $ai2 tronquée")
                    break
                }
                val ai4 = s.substring(i, i + 4)
                fields[ai4] = s.substring(i + 4, i + 10)
                i += 10
                continue
            }

            i += 2
            val fixedLen = FIXED_LENGTH[ai2]
            if (fixedLen != null) {
                if (i + fixedLen > s.length) {
                    errors.add("AI $ai2 tronquée (attendu $fixedLen caractères)")
                    break
                }
                fields[ai2] = s.substring(i, i + fixedLen)
                i += fixedLen
            } else {
                // Longueur variable : jusqu'au GS ou la fin
                val gsIndex = s.indexOf(GS, i)
                val end = if (gsIndex == -1) s.length else gsIndex
                fields[ai2] = s.substring(i, end)
                i = if (gsIndex == -1) s.length else gsIndex + 1
            }
        }

        // ---- Contrôles de cohérence ------------------------------------
        val gtin = fields["01"]
        if (gtin != null) {
            when {
                !gtin.all { it.isDigit() } ->
                    errors.add("GTIN non numérique")

                // Piège vérifié par test : un GTIN entièrement à zéro PASSE le
                // calcul modulo 10 (somme nulle -> clé 0). Sans ce garde-fou,
                // une image bruitée décodée en zéros serait acceptée.
                isDegenerate(gtin) ->
                    errors.add("GTIN dégénéré (que des zéros/chiffres identiques)")

                !isValidGtinChecksum(gtin) ->
                    errors.add("Clé de contrôle GTIN invalide (lecture douteuse)")
            }
        } else {
            errors.add("Aucun GTIN (AI 01) trouvé dans le code")
        }

        // Un CIP13 de médicament français commence par 34009 (les dispositifs
        // médicaux commencent par 3401). Avertissement seulement : on n'interdit
        // pas de scanner un produit importé ou un dispositif.
        cip13FromGtin(gtin)?.let {
            if (!it.startsWith("3400")) {
                notices.add("Ne ressemble pas à un CIP13 de médicament français")
            }
        }

        fields["17"]?.let {
            if (parseExpiry(it) == null) notices.add("Date de péremption illisible : $it")
        }

        // Longueurs maximales GS1 pour les champs alphanumériques variables.
        // Un dépassement signale presque toujours un séparateur GS manquant :
        // le champ a « avalé » l'AI suivante.
        fields["10"]?.let {
            if (it.length > MAX_VARIABLE_LENGTH)
                errors.add("Lot anormalement long (séparateur GS probablement absent)")
        }
        fields["21"]?.let {
            if (it.length > MAX_VARIABLE_LENGTH)
                notices.add("Numéro de série anormalement long")
        }

        return Gs1Data(raw = input, fields = fields, errors = errors, notices = notices)
    }

    /** Longueur max GS1 pour les AI alphanumériques variables (10, 21). */
    private const val MAX_VARIABLE_LENGTH = 20

    /** Un GTIN composé d'un seul chiffre répété n'est jamais un vrai produit. */
    private fun isDegenerate(gtin: String): Boolean =
        gtin.isNotEmpty() && gtin.all { it == gtin[0] }

    private fun cip13FromGtin(gtin: String?): String? = when {
        gtin == null -> null
        gtin.length == 14 && gtin.startsWith("0") -> gtin.substring(1)
        gtin.length == 13 -> gtin
        else -> null
    }

    /**
     * Vérifie la clé de contrôle GTIN (modulo 10, pondération 3/1 en partant
     * de la droite). C'est la meilleure défense contre une lecture partielle
     * ou corrompue : un DataMatrix mal décodé passera rarement ce test.
     */
    fun isValidGtinChecksum(gtin: String): Boolean {
        if (gtin.length !in listOf(8, 12, 13, 14) || !gtin.all { it.isDigit() }) return false
        val digits = gtin.map { it - '0' }
        val checkDigit = digits.last()
        val payload = digits.dropLast(1).reversed()
        var sum = 0
        payload.forEachIndexed { index, d ->
            sum += if (index % 2 == 0) d * 3 else d
        }
        val computed = (10 - (sum % 10)) % 10
        return computed == checkDigit
    }

    /**
     * Convertit une date GS1 AAMMJJ en ISO yyyy-MM-dd.
     *
     * Règle GS1 : un jour à "00" signifie « fin du mois ». On renvoie donc le
     * dernier jour réel du mois (28/29/30/31 selon l'année), et pas le 1er.
     * Le siècle est déduit selon la règle GS1 (fenêtre glissante de 50 ans).
     *
     * [currentYear] n'est paramétrable que pour les tests : la règle du
     * siècle dépend de l'année courante, et un test qui en dépend
     * implicitement changerait de résultat avec le temps.
     */
    fun parseExpiry(
        yymmdd: String,
        currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    ): String? {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return null
        val yy = yymmdd.substring(0, 2).toInt()
        val mm = yymmdd.substring(2, 4).toInt()
        val dd = yymmdd.substring(4, 6).toInt()
        if (mm !in 1..12 || dd !in 0..31) return null

        val currentCentury = (currentYear / 100) * 100
        var year = currentCentury + yy
        // Fenêtre glissante : une date > 50 ans dans le futur appartient au
        // siècle précédent (et inversement).
        if (year - currentYear > 50) year -= 100
        if (currentYear - year > 50) year += 100

        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, mm - 1)
        val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val day = if (dd == 0) lastDay else minOf(dd, lastDay)

        return String.format(Locale.US, "%04d-%02d-%02d", year, mm, day)
    }

    /** Identifiant stable d'une boîte physique, pour la déduplication. */
    fun boxKey(data: Gs1Data): String =
        listOfNotNull(data.cip13, data.lot, data.serial, data.expiryRaw).joinToString("|")
}
