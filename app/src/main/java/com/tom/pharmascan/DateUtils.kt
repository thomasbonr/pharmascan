package com.tom.pharmascan

/** ISO yyyy-MM-dd → JJ/MM/AAAA, format lisible en français. */
fun frenchDate(iso: String): String {
    val p = iso.split("-")
    return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
}
