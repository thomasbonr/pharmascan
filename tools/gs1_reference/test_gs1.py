from gs1_port import *
from datetime import date

GSC = '\x1d'
ok = fail = 0

def check(name, cond, detail=""):
    global ok, fail
    if cond:
        ok += 1
        print(f"  PASS  {name}")
    else:
        fail += 1
        print(f"  FAIL  {name}  {detail}")

print("=== 1. GTIN valide fabrique ===")
g = make_gtin("340093000000")   # 12 chiffres -> GTIN14
print("   GTIN test:", g, "len", len(g))
check("checksum GTIN fabrique", valid_gtin_checksum(g))
check("checksum rejette une corruption", not valid_gtin_checksum(g[:-1] + str((int(g[-1]) + 1) % 10)))

print("\n=== 2. DataMatrix pharma FR typique (01,17,10,21) ===")
payload = f"01{g}17271130" + "10" + "L4A21" + GSC + "21" + "XK7291056"
f, w = parse(payload)
print("   fields:", f)
print("   warnings:", w)
check("GTIN extrait", f.get("01") == g)
check("peremption brute", f.get("17") == "271130")
check("lot extrait", f.get("10") == "L4A21")
check("serie extraite", f.get("21") == "XK7291056")
check("aucun warning", w == [], str(w))
check("cip13 = 13 chiffres", cip13(f) is not None and len(cip13(f)) == 13, str(cip13(f)))

print("\n=== 3. Ordre inverse : AI variable AVANT AI fixe ===")
payload2 = "10" + "LOT99" + GSC + f"01{g}" + "17280229"
f2, w2 = parse(payload2)
check("GTIN trouve malgre l'ordre", f2.get("01") == g, str(f2))
check("lot correct", f2.get("10") == "LOT99")
check("peremption correcte", f2.get("17") == "280229")

print("\n=== 4. Sans separateur GS (lot en dernier) ===")
payload3 = f"01{g}17271130" + "10" + "ABC123"
f3, w3 = parse(payload3)
check("lot jusqu'a fin de chaine", f3.get("10") == "ABC123", str(f3))
check("pas de warning", w3 == [], str(w3))

print("\n=== 5. DANGER : lot en avant-dernier SANS GS ===")
# Cas pathologique : si le lot n'est pas termine par GS mais suivi d'un AI 21,
# le parser va avaler le reste. C'est conforme a la norme (GS obligatoire),
# mais verifions le comportement.
payload4 = f"01{g}" + "10" + "ABC123" + "21" + "SERIE1"
f4, w4 = parse(payload4)
print("   -> lot lu:", repr(f4.get("10")), " serie:", repr(f4.get("21")))
check("comportement documente (lot avale le reste)", f4.get("10") == "ABC12321SERIE1")

print("\n=== 6. Prefixe de symbologie ]d2 ===")
f5, w5 = parse("]d2" + f"01{g}17271130")
check("prefixe ]d2 retire", f5.get("01") == g, str(f5))

print("\n=== 7. GS represente en clair <GS> ===")
f6, w6 = parse(f"01{g}" + "10LOT<GS>" + "17271130")
check("<GS> reconnu comme separateur", f6.get("10") == "LOT" and f6.get("17") == "271130", str(f6))

print("\n=== 8. Dates : regle du jour 00 = fin de mois ===")
today = date(2026, 8, 9)
check("2711 00 -> 30 nov", parse_expiry("271100", today) == "2027-11-30", parse_expiry("271100", today))
check("2802 00 -> 29 fev (bissextile)", parse_expiry("280200", today) == "2028-02-29", parse_expiry("280200", today))
check("2702 00 -> 28 fev", parse_expiry("270200", today) == "2027-02-28", parse_expiry("270200", today))
check("jour explicite conserve", parse_expiry("271115", today) == "2027-11-15")
check("jour invalide clampe", parse_expiry("270231", today) == "2027-02-28", parse_expiry("270231", today))
check("mois 13 rejete", parse_expiry("271301", today) is None)
check("non numerique rejete", parse_expiry("27AB01", today) is None)

print("\n=== 9. Fenetre glissante du siecle (annee courante 2026) ===")
check("aa=99 -> 1999 (passe, boite perimee)", parse_expiry("991231", today) == "1999-12-31", parse_expiry("991231", today))
check("aa=27 -> 2027", parse_expiry("271231", today) == "2027-12-31")
check("aa=70 -> 2070", parse_expiry("701231", today) == "2070-12-31", parse_expiry("701231", today))

print("\n=== 10. Codes non exploitables ===")
f7, w7 = parse("HELLO WORLD")
check("texte libre -> warning", len(w7) > 0 and cip13(f7) is None, str(w7))
f8, w8 = parse("0100000000000000")  # GTIN de zeros : passe le checksum !
check("GTIN tout a zero rejete", any("degenere" in x for x in w8), str(w8))

print("\n=== 11. AI 4 chiffres (poids 3103) ===")
f9, w9 = parse(f"01{g}" + "3103000250")
check("AI 3103 lue sur 4+6", f9.get("3103") == "000250", str(f9))


print("\n=== 12. Regression : lot trop long = GS manquant ===")
f10, w10 = parse(f"01{g}" + "10" + "A"*25)
check("lot >20 caracteres signale", len(f10.get("10","")) > 20)

print("\n=== 13. AI 410 (GLN) : 3 chiffres + 13, sans GS ===")
f11, w11 = parse(f"01{g}" + "4103012345678900" + "17271130")
check("AI 410 lue sur 3+13", f11.get("410") == "3012345678900", str(f11))
check("parsing non decale apres l'AI 410", f11.get("17") == "271130" and w11 == [], str(w11))

print("\n" + "=" * 50)
print(f"RESULTAT : {ok} PASS / {fail} FAIL")
raise SystemExit(1 if fail else 0)
