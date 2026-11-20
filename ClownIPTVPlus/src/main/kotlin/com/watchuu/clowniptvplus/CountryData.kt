package com.watchuu.clowniptvplus

/** Region metadata for the playlist; channel names look like "IN | Star Plus HD". */
object CountryData {
    /** Sentinel shown in the settings dialog, meaning "do not filter". */
    const val ALL = "All countries"

    val FEATURED = listOf("India", "PPV & Events", "United States", "United Kingdom", "Canada")
    val TAIL = listOf("24/7", "Other", "Adult (18+)")

    /** prefix (upper case) -> region row name. */
    val PREFIX_ROWS: Map<String, String> = buildMap {
        row("India", "IN", "ASIA-IN", "UK-IN", "IND", "INDIA")
        row("PPV & Events", "PPV", "VIP")
        row("United States", "US", "USA")
        row("United Kingdom", "UK")
        row("Canada", "CA")
        row("Pakistan", "PK", "VIP - PK", "VIP-PK")
        row("Bangladesh", "BD")
        row("Sri Lanka", "SRI LANKA", "LK", "SL")
        row("Arabic", "AR", "AR-SP", "AR-KIDS", "AR-VIP", "AR_NS", "SH", "ISLAM", "BEIN", "OSN", "MBC", "MYHD", "ROT", "ART", "EGY", "IRAQ", "KSA", "UAE", "KUW", "KU", "QATAR", "BAHR", "OMAN", "YEMEN", "JOR", "LEB", "SYR", "PALES", "LBY", "TUN", "DZ", "MA", "SUDAN")
        row("France", "FR", "FR-CAR", "FR-REU")
        row("Germany", "DE")
        row("Spain", "ES")
        row("Italy", "IT")
        row("Portugal", "PT")
        row("Turkey", "TR")
        row("Netherlands & Belgium", "NL", "BE")
        row("Nordic", "SW", "SE", "DK", "NO", "FI", "IC")
        row("Albania", "AL")
        row("Ex-Yugoslavia", "EXYU", "SRB")
        row("Greece", "GR")
        row("Poland", "PL")
        row("Romania", "RO")
        row("Russia", "RU")
        row("Iran & Afghanistan", "IR", "AF")
        row("Africa", "DSTV")
        row("Australia", "AU")
        row("Latin America", "MX", "ARG", "CO", "LATINO", "BR", "LATIN-BR", "UY", "DO", "CR", "CAR")
    }

    private fun MutableMap<String, String>.row(name: String, vararg prefixes: String) {
        prefixes.forEach { put(it, name) }
    }

    /** Every region row in display order: featured, then alphabetical, then the tail. */
    val ROW_ORDER: List<String> =
        FEATURED + PREFIX_ROWS.values.filter { it !in FEATURED }.toSortedSet().toList() + TAIL

    /** "All countries" followed by every row. */
    val SELECTABLE: List<String> = listOf(ALL) + ROW_ORDER
}
