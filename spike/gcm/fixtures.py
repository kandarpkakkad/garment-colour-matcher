"""The fixture format the Kotlin engine is checked against. Accuracy LLD §3."""
from .estimate import ESTIMATORS
from .labels import NAMES


def _f(x):
    return "%.10g" % float(x)


def _vec(v):
    return "-" if v is None else " ".join(_f(x) for x in v)


def write(path, name, img8, labels, truth, analysis, neutral_at, neutral_result):
    h, w = labels.shape
    est = analysis.estimate
    lines = [
        "gcm-fixture 1",
        f"name {name}",
        f"size {w} {h}",
        f"truth {_vec(truth)}",
    ]
    for k in ESTIMATORS:
        lines.append(f"expect.{k} {_vec(est.answers.get(k))}")
    lines += [
        f"expect.combined {_vec(est.combined)}",
        f"expect.disagreement {'-' if est.disagreement is None else _f(est.disagreement)}",
        f"expect.support {_f(est.support)}",
        f"expect.score {_f(est.score)}",
        f"expect.level {est.level}",
        f"expect.reason {est.reason or '-'}",
        f"expect.method {analysis.method}",
        f"expect.matrix {' '.join(_f(x) for x in analysis.matrix.reshape(-1))}",
    ]
    for g in analysis.garments:
        lines.append(f"expect.garment {NAMES[g.code]} {_f(g.fraction)} {int(g.clipped)} "
                     f"{_vec(g.lab)}")
    u, v = neutral_at
    kind, value = neutral_result
    if kind == "ok":
        lines.append(f"expect.neutral {_f(u)} {_f(v)} ok {_vec(value)}")
    else:
        lines.append(f"expect.neutral {_f(u)} {_f(v)} refused {value}")
    lines.append("pixels")
    for y in range(h):
        lines.append(" ".join("%02X%02X%02X%02X" % (img8[y, x, 0], img8[y, x, 1], img8[y, x, 2],
                                                   labels[y, x]) for x in range(w)))
    with open(path, "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
