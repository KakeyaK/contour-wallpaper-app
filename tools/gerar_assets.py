#!/usr/bin/env python3
"""
gerar_assets.py — gera os PNGs transparentes de curvas de nivel usados como
assets do live wallpaper.

    pip install numpy matplotlib
    python gerar_assets.py

Saida (pasta ./assets):
    linhas_cover.png     1080x2520  todas as curvas, camada unica
    linhas1_cover.png    faixa de altitude baixa
    linhas2_cover.png    faixa intermediaria
    linhas3_cover.png    faixa alta
    linhas_main.png      1968x2184  idem, para a tela interna do dobravel
    linhas1_main.png ... linhas3_main.png

As curvas sao desenhadas em BRANCO com fundo transparente: no app, um
PorterDuffColorFilter(cor, SRC_IN) troca a cor preservando o canal alfa.

Os dois conjuntos saem do MESMO campo de relevo e na mesma escala — o recorte
da tela externa e a faixa central da interna, entao ao desdobrar o aparelho o
mapa continua em vez de trocar de imagem.
"""

import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

SEED = 42          # mesmo seed = mesmo relevo, sempre
OCTAVES = 5        # 2 = formas amplas e organicas, 6+ = relevo detalhado
LEVELS = 34        # quantidade de curvas
GRID_H = 700       # resolucao de amostragem do campo
COVER = (1080, 2520)
MAIN = (1968, 2184)


def fractal_noise(h, w, octaves, persistence=0.55, rng=None):
    """Ruido fractal suave via filtragem passa-baixa no dominio da frequencia."""
    rng = rng or np.random.default_rng()
    field = np.zeros((h, w))
    amplitude, total = 1.0, 0.0

    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    radius = np.sqrt(fy ** 2 + fx ** 2)

    for i in range(octaves):
        cutoff = 0.004 * (2 ** i)
        spectrum = np.fft.fft2(rng.standard_normal((h, w)))
        spectrum *= np.exp(-(radius / cutoff) ** 2)
        layer = np.real(np.fft.ifft2(spectrum))
        std = layer.std()
        if std > 0:
            layer /= std
        field += amplitude * layer
        total += amplitude
        amplitude *= persistence

    field /= total
    field -= field.min()
    return field / field.max()


def add_flow(field, rng, strength=0.35):
    """Distorce o campo com um gradiente diagonal, evitando manchas isoladas."""
    h, w = field.shape
    yy, xx = np.mgrid[0:h, 0:w]
    angle = rng.uniform(0, np.pi)
    ramp = np.cos(angle) * xx / w + np.sin(angle) * yy / h
    return (1 - strength) * field + strength * ramp


def build_field(width, height, seed, octaves, grid_h=GRID_H):
    rng = np.random.default_rng(seed)
    grid_w = max(2, int(round(grid_h * width / height)))
    return add_flow(fractal_noise(grid_h, grid_w, octaves, rng=rng), rng)


def center_strip(field):
    """Recorta do campo da tela interna a faixa com a proporcao da tela externa."""
    frac = (COVER[0] / COVER[1]) / (MAIN[0] / MAIN[1])
    grid_h, grid_w = field.shape
    strip = max(2, int(round(grid_w * frac)))
    x0 = (grid_w - strip) // 2
    return field[:, x0:x0 + strip]


def contour_levels(field, levels=LEVELS):
    """Niveis de altitude. Calculados UMA vez, no campo mestre, e reusados nos dois
    conjuntos — se cada recorte calculasse os seus proprios, as curvas da tela externa
    cairiam em altitudes diferentes das da interna e o mapa saltaria ao desdobrar."""
    return np.linspace(field.min(), field.max(), levels + 2)[1:-1]


def export_layers(field, outdir, width, height, tag, levels, bands=3,
                  glow=True, dpi=100):
    grid_h, grid_w = field.shape
    xs = np.linspace(0, grid_w, grid_w)
    ys = np.linspace(0, grid_h, grid_h)
    all_levels = np.asarray(levels)

    groups = {f"linhas_{tag}.png": (all_levels, 0)}
    for i, idx in enumerate(np.array_split(np.arange(len(all_levels)), bands), 1):
        groups[f"linhas{i}_{tag}.png"] = (all_levels[idx], int(idx[0]))

    for fname, (lv, offset) in groups.items():
        if len(lv) == 0:
            continue
        fig = plt.figure(figsize=(width / dpi, height / dpi), dpi=dpi)
        fig.patch.set_alpha(0.0)
        ax = fig.add_axes([0, 0, 1, 1])
        ax.set_axis_off()
        ax.patch.set_alpha(0.0)

        if glow:                      # halo suave por tras das linhas
            ax.contour(xs, ys, field, levels=lv, colors="white",
                       linewidths=4.5, alpha=0.12)
        # espessura alternada: a cada 5 curvas uma mais grossa, como num mapa real
        widths = [1.9 if (offset + i) % 5 == 0 else 0.85 for i in range(len(lv))]
        ax.contour(xs, ys, field, levels=lv, colors="white",
                   linewidths=widths, alpha=0.92)

        ax.set_xlim(0, grid_w)
        ax.set_ylim(grid_h, 0)
        path = os.path.join(outdir, fname)
        fig.savefig(path, dpi=dpi, pad_inches=0, transparent=True)
        plt.close(fig)
        print(path)


def main():
    outdir = "assets"
    os.makedirs(outdir, exist_ok=True)
    master = build_field(*MAIN, SEED, OCTAVES)
    levels = contour_levels(master)
    export_layers(master, outdir, *MAIN, "main", levels)
    export_layers(center_strip(master), outdir, *COVER, "cover", levels)


if __name__ == "__main__":
    main()
