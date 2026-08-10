"""Port fidèle de Gs1Parser.kt pour valider l'algorithme avant de figer le Kotlin."""
import calendar
from datetime import date

GS = '\x1d'

FIXED_LENGTH = {
    "00": 18, "01": 14, "02": 14, "03": 14, "04": 16,
    "11": 6, "12": 6, "13": 6, "14": 6, "15": 6, "16": 6,
    "17": 6, "18": 6, "19": 6, "20": 2, "41": 13,
}


def is_four_digit_measure_ai(ai2):
    return len(ai2) == 2 and ai2[0] == '3' and ai2[1] in '123456'


def normalize(s):
    s = s.strip()
    for p in ("]d2", "]d1", "]C1", "]e0", "]Q3"):
        if s.startswith(p):
            s = s[len(p):]
            break
    s = s.replace("<GS>", GS).replace("{GS}", GS).replace('\u241d', GS).replace('\x1e', GS)
    return s


def valid_gtin_checksum(g):
    if len(g) not in (8, 12, 13, 14) or not g.isdigit():
        return False
    digits = [int(c) for c in g]
    check = digits[-1]
    payload = digits[:-1][::-1]
    total = sum(d * 3 if i % 2 == 0 else d for i, d in enumerate(payload))
    return (10 - total % 10) % 10 == check


def parse_expiry(yymmdd, today=None):
    if len(yymmdd) != 6 or not yymmdd.isdigit():
        return None
    yy, mm, dd = int(yymmdd[:2]), int(yymmdd[2:4]), int(yymmdd[4:6])
    if not (1 <= mm <= 12) or not (0 <= dd <= 31):
        return None
    cur = (today or date.today()).year
    year = (cur // 100) * 100 + yy
    if year - cur > 50:
        year -= 100
    if cur - year > 50:
        year += 100
    last = calendar.monthrange(year, mm)[1]
    day = last if dd == 0 else min(dd, last)
    return f"{year:04d}-{mm:02d}-{day:02d}"


def parse(raw):
    s = normalize(raw)
    fields, warnings = {}, []
    i = 0
    while i < len(s):
        if s[i] == GS:
            i += 1
            continue
        if i + 2 > len(s):
            warnings.append(f"Fin de chaine inattendue a {i}")
            break
        ai2 = s[i:i+2]
        if not ai2.isdigit():
            warnings.append(f"AI non numerique {ai2} a {i}")
            break
        if is_four_digit_measure_ai(ai2):
            if i + 10 > len(s):
                warnings.append(f"AI {ai2} tronquee")
                break
            fields[s[i:i+4]] = s[i+4:i+10]
            i += 10
            continue
        i += 2
        fixed = FIXED_LENGTH.get(ai2)
        if fixed is not None:
            if i + fixed > len(s):
                warnings.append(f"AI {ai2} tronquee")
                break
            fields[ai2] = s[i:i+fixed]
            i += fixed
        else:
            gi = s.find(GS, i)
            end = len(s) if gi == -1 else gi
            fields[ai2] = s[i:end]
            i = len(s) if gi == -1 else gi + 1

    g = fields.get("01")
    if g is None:
        warnings.append("Aucun GTIN")
    elif not g.isdigit():
        warnings.append("GTIN non numerique")
    elif len(set(g)) == 1:
        warnings.append("GTIN degenere")
    elif not valid_gtin_checksum(g):
        warnings.append("Cle de controle GTIN invalide")
    if "17" in fields and parse_expiry(fields["17"]) is None:
        warnings.append("Date illisible")
    return fields, warnings


def cip13(fields):
    g = fields.get("01")
    if not g:
        return None
    if len(g) == 14 and g.startswith("0"):
        return g[1:]
    if len(g) == 13:
        return g
    return None


def make_gtin(cip):
    """Fabrique un GTIN-14 valide a partir d'un CIP13 partiel (12 chiffres) pour les tests."""
    body = "0" + cip  # 13 chiffres sans cle
    digits = [int(c) for c in body]
    payload = digits[::-1]
    total = sum(d * 3 if i % 2 == 0 else d for i, d in enumerate(payload))
    return body + str((10 - total % 10) % 10)
